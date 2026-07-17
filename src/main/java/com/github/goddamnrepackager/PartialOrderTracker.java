package com.github.goddamnrepackager;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks orders that have been TAKEN OVER by partial repackaging (see
 * PartialRepackager), stored as world-level SavedData alongside SharedPackagePool.
 *
 * Why this exists: once we consume ANY fragment of an order, vanilla's
 * isOrderComplete can never pass again (the 2D fragment grid has gaps at the
 * consumed slots), so we become responsible for the order's remaining lifetime.
 * For each taken-over order we must remember:
 *
 *  - leftovers: items from consumed fragments that didn't complete a craft yet
 *    (e.g. 5 of 8 planks for a chest). They are the raw materials for future
 *    partial passes — NOT exported until the order closes out.
 *  - consumed fragment slots + grid bounds: the cumulative equivalent of
 *    isOrderComplete, so we know when the LAST fragment has been consumed and
 *    the order can be closed out via a vanilla repack().
 *  - context (full PackageOrderWithCrafts) + address: needed to craft and to
 *    build pseudo-fragments for the final pass.
 *
 * Lifecycle mirrors SharedPackagePool (vault-centric): entries survive chunk
 * unload / restart; vault teardown drains leftovers as raw item entities
 * (ConnectivityHandlerMixin); vault reshape migrates the key
 * (ItemVaultBlockEntityMixin); order close-out forgets the entry.
 */
public class PartialOrderTracker extends SavedData {

    private static final String DATA_ID = "gdr_partial_order_tracker";

    /** vault box -> (orderId -> tracked state) */
    private final Map<BoundingBox, Map<Integer, TrackedOrder>> orders = new HashMap<>();

    public PartialOrderTracker() {}

    /**
     * Per-order takeover state. The fragment grid mirrors vanilla's
     * isOrderComplete semantics: an order is a 2D grid of (LinkIndex, Index)
     * slots, complete when every slot from (0,0) up to each link's IsFinal
     * fragment and the final link's IsFinalLink fragment is present. We apply
     * the same check to the CUMULATIVE CONSUMED set instead of the
     * currently-present set.
     */
    public static class TrackedOrder {
        public int orderId;
        public String address = "";
        public PackageOrderWithCrafts context;
        public InventorySummary leftovers = new InventorySummary();
        public final Set<Long> consumedSlots = new HashSet<>();
        public int finalLinkIndex = -1;                       // -1 = not yet seen
        public final Map<Integer, Integer> finalIndexPerLink = new HashMap<>();

        private static long slotKey(int link, int index) {
            return ((long) link << 32) | (index & 0xffffffffL);
        }

        /** Record freshly consumed fragments (slots + grid bounds). */
        public void recordConsumption(List<ItemStack> fragments) {
            for (ItemStack f : fragments) {
                int link = PackageItem.getLinkIndex(f);
                int index = PackageItem.getIndex(f);
                if (link < 0 || index < 0) continue; // not a fragment: ignore defensively
                consumedSlots.add(slotKey(link, index));
                if (PackageItem.isFinalLink(f)) finalLinkIndex = link;
                if (PackageItem.isFinal(f)) finalIndexPerLink.put(link, index);
            }
        }

        /**
         * Would consuming newFragments (on top of everything already consumed)
         * complete the grid? Mirrors isOrderComplete on the cumulative set:
         * all bounds known AND every slot in [0..finalLink]x[0..finalIndex(link)]
         * present. Computed on a tentative copy — does NOT mutate this tracker.
         */
        public boolean wouldComplete(List<ItemStack> newFragments) {
            Set<Long> slots = new HashSet<>(consumedSlots);
            int fl = finalLinkIndex;
            Map<Integer, Integer> fi = new HashMap<>(finalIndexPerLink);
            for (ItemStack f : newFragments) {
                int link = PackageItem.getLinkIndex(f);
                int index = PackageItem.getIndex(f);
                if (link < 0 || index < 0) continue;
                slots.add(slotKey(link, index));
                if (PackageItem.isFinalLink(f)) fl = link;
                if (PackageItem.isFinal(f)) fi.put(link, index);
            }
            if (fl < 0) return false;
            for (int link = 0; link <= fl; link++) {
                Integer last = fi.get(link);
                if (last == null) return false;
                for (int index = 0; index <= last; index++)
                    if (!slots.contains(slotKey(link, index))) return false;
            }
            return true;
        }
    }

    public static PartialOrderTracker load(CompoundTag root) {
        PartialOrderTracker tracker = new PartialOrderTracker();
        ListTag vaultList = root.getList("Vaults", Tag.TAG_COMPOUND);
        for (int i = 0; i < vaultList.size(); i++) {
            CompoundTag vaultEntry = vaultList.getCompound(i);
            BoundingBox box = new BoundingBox(
                    vaultEntry.getInt("MinX"), vaultEntry.getInt("MinY"), vaultEntry.getInt("MinZ"),
                    vaultEntry.getInt("MaxX"), vaultEntry.getInt("MaxY"), vaultEntry.getInt("MaxZ"));
            Map<Integer, TrackedOrder> orderMap = new HashMap<>();
            ListTag orderList = vaultEntry.getList("Orders", Tag.TAG_COMPOUND);
            for (int j = 0; j < orderList.size(); j++) {
                CompoundTag o = orderList.getCompound(j);
                TrackedOrder t = new TrackedOrder();
                t.orderId = o.getInt("OrderId");
                t.address = o.getString("Address");
                if (o.contains("Context"))
                    t.context = PackageOrderWithCrafts.read(o.getCompound("Context"));
                if (o.contains("Leftovers"))
                    t.leftovers = InventorySummary.read(o.getCompound("Leftovers"));
                for (long s : o.getLongArray("Slots"))
                    t.consumedSlots.add(s);
                t.finalLinkIndex = o.getInt("FinalLink");
                CompoundTag fi = o.getCompound("FinalIndices");
                for (String key : fi.getAllKeys())
                    t.finalIndexPerLink.put(Integer.parseInt(key), fi.getInt(key));
                orderMap.put(t.orderId, t);
            }
            if (!orderMap.isEmpty()) tracker.orders.put(box, orderMap);
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag vaultList = new ListTag();
        for (Map.Entry<BoundingBox, Map<Integer, TrackedOrder>> e : orders.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag vaultEntry = new CompoundTag();
            BoundingBox b = e.getKey();
            vaultEntry.putInt("MinX", b.minX());
            vaultEntry.putInt("MinY", b.minY());
            vaultEntry.putInt("MinZ", b.minZ());
            vaultEntry.putInt("MaxX", b.maxX());
            vaultEntry.putInt("MaxY", b.maxY());
            vaultEntry.putInt("MaxZ", b.maxZ());
            ListTag orderList = new ListTag();
            for (TrackedOrder t : e.getValue().values()) {
                CompoundTag o = new CompoundTag();
                o.putInt("OrderId", t.orderId);
                o.putString("Address", t.address);
                if (t.context != null) o.put("Context", t.context.write());
                o.put("Leftovers", t.leftovers.write());
                o.putLongArray("Slots", new java.util.ArrayList<>(t.consumedSlots));
                o.putInt("FinalLink", t.finalLinkIndex);
                CompoundTag fi = new CompoundTag();
                for (Map.Entry<Integer, Integer> fe : t.finalIndexPerLink.entrySet())
                    fi.putInt(String.valueOf(fe.getKey()), fe.getValue());
                o.put("FinalIndices", fi);
                orderList.add(o);
            }
            vaultEntry.put("Orders", orderList);
            vaultList.add(vaultEntry);
        }
        root.put("Vaults", vaultList);
        return root;
    }

    public static PartialOrderTracker get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PartialOrderTracker::load, PartialOrderTracker::new, DATA_ID);
    }

    /** Tracked state for (vault, orderId), or null if the order isn't taken over. */
    public TrackedOrder get(BoundingBox vault, int orderId) {
        Map<Integer, TrackedOrder> m = orders.get(vault);
        return m == null ? null : m.get(orderId);
    }

    /**
     * Create-or-update after a partial pass: store the post-craft leftover pool
     * and record which fragments were consumed. context/address refresh from the
     * latest fragments (they're constant per order, but the first pass may see
     * them before the tracker exists).
     */
    public void update(BoundingBox vault, int orderId, InventorySummary leftovers,
                       List<ItemStack> consumedFragments, PackageOrderWithCrafts context, String address) {
        TrackedOrder t = orders.computeIfAbsent(vault, k -> new HashMap<>())
                .computeIfAbsent(orderId, k -> new TrackedOrder());
        t.orderId = orderId;
        t.leftovers = leftovers;
        t.recordConsumption(consumedFragments);
        if (context != null) t.context = context;
        if (address != null && !address.isEmpty()) t.address = address;
        setDirty();
    }

    /** Order closed out (final pass done) — drop its tracking entry. */
    public void forget(BoundingBox vault, int orderId) {
        Map<Integer, TrackedOrder> m = orders.get(vault);
        if (m == null) return;
        if (m.remove(orderId) != null) setDirty();
        if (m.isEmpty()) orders.remove(vault);
    }

    /** Vault reshape: move all tracked orders to the new geometry key. */
    public void migrateKey(BoundingBox oldBox, BoundingBox newBox) {
        if (oldBox.equals(newBox)) return;
        Map<Integer, TrackedOrder> m = orders.remove(oldBox);
        if (m == null || m.isEmpty()) return;
        orders.put(newBox, m);
        setDirty();
        if (GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-PARTIAL] migrated {} tracked order(s) on vault reshape {} -> {}", m.size(), oldBox, newBox);
        }
    }

    /**
     * Vault destroyed: drop every tracked order's leftover items as raw item
     * entities (they're unconsumed raw materials, not packages). In-vault
     * fragments of these orders drop separately via vanilla vault breakage —
     * disjoint sets, no double-counting. Idempotent.
     */
    public void drainAndDrop(BoundingBox vault, Level level, BlockPos pos) {
        Map<Integer, TrackedOrder> m = orders.remove(vault);
        if (m == null || m.isEmpty()) return;
        Vec3 dropPos = Vec3.atCenterOf(pos);
        int dropped = 0;
        for (TrackedOrder t : m.values()) {
            for (BigItemStack bis : t.leftovers.getStacks()) {
                int remaining = Math.max(0, bis.count);
                while (remaining > 0) {
                    int n = Math.min(remaining, bis.stack.getMaxStackSize());
                    ItemStack stack = bis.stack.copy();
                    stack.setCount(n);
                    if (!stack.isEmpty()) {
                        ItemEntity entity = new ItemEntity(level, dropPos.x, dropPos.y + 0.5, dropPos.z, stack);
                        entity.setDefaultPickUpDelay();
                        level.addFreshEntity(entity);
                        dropped++;
                    }
                    remaining -= n;
                }
            }
        }
        setDirty();
        if (GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-PARTIAL] drained {} item(s) from {} tracked order(s) at vault {}",
                    dropped, m.size(), pos);
        }
    }
}
