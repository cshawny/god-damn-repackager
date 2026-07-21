package com.github.goddamnrepackager;

import com.mojang.serialization.DataResult;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks orders that have been TAKEN OVER by partial repackaging (see
 * {@link PartialRepackager}), stored as world-level SavedData alongside
 * {@link SharedPackagePool}.
 *
 * <p><b>0.5.1 change:</b> keyed by vault {@link UUID} (not BoundingBox), in lockstep
 * with {@link SharedPackagePool}. See TECHNICAL.md §3.11 for why.</p>
 *
 * <p>Why this exists: once we consume ANY fragment of an order, vanilla's
 * {@code isOrderComplete} can never pass again (the 2D fragment grid has gaps at the
 * consumed slots), so we become responsible for the order's remaining lifetime.
 * For each taken-over order we must remember:</p>
 * <ul>
 *   <li>leftovers: items from consumed fragments that didn't complete a craft yet
 *       (e.g. 5 of 8 planks for a chest). Raw materials for future partial passes —
 *       NOT exported until the order closes out.</li>
 *   <li>consumed fragment slots + grid bounds: the cumulative equivalent of
 *       {@code isOrderComplete}, so we know when the LAST fragment has been consumed
 *       and the order can be closed out via a vanilla {@code repack()}.</li>
 *   <li>context (full {@link PackageOrderWithCrafts}) + address: needed to craft and
 *       to build pseudo-fragments for the final pass.</li>
 * </ul>
 *
 * <p>Lifecycle mirrors {@link SharedPackagePool} (vault-centric): entries survive chunk
 * unload / restart; vault teardown drains leftovers as raw item entities
 * ({@code ConnectivityHandlerMixin}); two-vault merge migrates the key (see
 * {@link SharedPackagePool#resolveMergeWinner}); order close-out forgets the entry.</p>
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * Mirrors SharedPackagePool's SavedData pattern: save/load carry a
 * HolderLookup.Provider (ItemStack is data-component based since 1.20.5, and
 * InventorySummary / PackageOrderWithCrafts codecs require registry access).
 * Both classes dropped their read(CompoundTag)/write() helpers in 1.21.1 — we
 * run their CODEC directly through NbtOps + the registries-backed context.
 */
public class PartialOrderTracker extends SavedData {

    private static final String DATA_ID = "gdr_partial_order_tracker";

    /** vault UUID -> (orderId -> tracked state) */
    private final Map<UUID, Map<Integer, TrackedOrder>> orders = new HashMap<>();

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

    /**
     * Load from NBT. Detects the legacy BoundingBox-keyed format and clears on upgrade.
     *
     * @param registries needed to decode ItemStack components inside the
     *                   PackageOrderWithCrafts / InventorySummary codecs
     */
    public static PartialOrderTracker load(CompoundTag root, HolderLookup.Provider registries) {
        PartialOrderTracker tracker = new PartialOrderTracker();
        ListTag vaultList = root.getList("Vaults", Tag.TAG_COMPOUND);
        if (vaultList.isEmpty()) return tracker;

        // Legacy-format probe (0.5.0 BoundingBox-keyed).
        CompoundTag sample = vaultList.getCompound(0);
        if (sample.contains("MinX")) {
            int lostOrders = 0;
            for (int i = 0; i < vaultList.size(); i++) {
                lostOrders += vaultList.getCompound(i).getList("Orders", Tag.TAG_COMPOUND).size();
            }
            GodDamnRepackager.LOGGER.warn(
                    "[GDR-PARTIAL] detected legacy BoundingBox-keyed SavedData ({} tracked order(s)); "
                            + "clearing on upgrade to 0.5.1 (alpha break — see changelog)",
                    lostOrders);
            return tracker;
        }

        for (int i = 0; i < vaultList.size(); i++) {
            CompoundTag vaultEntry = vaultList.getCompound(i);
            if (!vaultEntry.hasUUID("VaultId")) continue;
            UUID id = vaultEntry.getUUID("VaultId");
            Map<Integer, TrackedOrder> orderMap = new HashMap<>();
            ListTag orderList = vaultEntry.getList("Orders", Tag.TAG_COMPOUND);
            for (int j = 0; j < orderList.size(); j++) {
                CompoundTag o = orderList.getCompound(j);
                TrackedOrder t = new TrackedOrder();
                t.orderId = o.getInt("OrderId");
                t.address = o.getString("Address");
                if (o.contains("Context"))
                    t.context = decodeContext(o.getCompound("Context"), registries);
                if (o.contains("Leftovers"))
                    t.leftovers = decodeSummary(o.getCompound("Leftovers"), registries);
                for (long s : o.getLongArray("Slots"))
                    t.consumedSlots.add(s);
                t.finalLinkIndex = o.getInt("FinalLink");
                CompoundTag fi = o.getCompound("FinalIndices");
                for (String key : fi.getAllKeys())
                    t.finalIndexPerLink.put(Integer.parseInt(key), fi.getInt(key));
                orderMap.put(t.orderId, t);
            }
            if (!orderMap.isEmpty()) tracker.orders.put(id, orderMap);
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag vaultList = new ListTag();
        for (Map.Entry<UUID, Map<Integer, TrackedOrder>> e : orders.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag vaultEntry = new CompoundTag();
            vaultEntry.putUUID("VaultId", e.getKey());
            ListTag orderList = new ListTag();
            for (TrackedOrder t : e.getValue().values()) {
                CompoundTag o = new CompoundTag();
                o.putInt("OrderId", t.orderId);
                o.putString("Address", t.address);
                if (t.context != null) o.put("Context", encodeContext(t.context, registries));
                o.put("Leftovers", encodeSummary(t.leftovers, registries));
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

    /**
     * Get or create the world-level tracker. On 1.21.1, {@link SavedData.Factory} bundles
     * the new-instance supplier and the (Tag, HolderLookup.Provider) deserializer.
     */
    public static PartialOrderTracker get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        PartialOrderTracker::new,
                        (tag, registries) -> load(tag, registries),
                        null /* DataFixType — none, we handle versioning ourselves */),
                DATA_ID);
    }

    // --- Codec helpers ---
    // PackageOrderWithCrafts.CODEC and InventorySummary.CODEC both compose
    // ItemStack codecs (which need registry access for components), so we run
    // them through a registries-aware serialization context (same pattern as
    // SharedPackagePool's BigItemStack helpers).

    private static PackageOrderWithCrafts decodeContext(CompoundTag tag, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return PackageOrderWithCrafts.CODEC.parse(ops, tag)
                .result().orElse(null);
    }

    private static CompoundTag encodeContext(PackageOrderWithCrafts context, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<Tag> result = PackageOrderWithCrafts.CODEC.encodeStart(ops, context);
        Tag tag = result.result().orElse(null);
        return tag instanceof CompoundTag ct ? ct : new CompoundTag();
    }

    private static InventorySummary decodeSummary(CompoundTag tag, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return InventorySummary.CODEC.parse(ops, tag)
                .result().orElse(new InventorySummary());
    }

    private static CompoundTag encodeSummary(InventorySummary summary, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<Tag> result = InventorySummary.CODEC.encodeStart(ops, summary);
        Tag tag = result.result().orElse(null);
        return tag instanceof CompoundTag ct ? ct : new CompoundTag();
    }

    /** Tracked state for (vault, orderId), or null if the order isn't taken over. */
    public TrackedOrder get(UUID vault, int orderId) {
        Map<Integer, TrackedOrder> m = orders.get(vault);
        return m == null ? null : m.get(orderId);
    }

    /**
     * Create-or-update after a partial pass: store the post-craft leftover pool
     * and record which fragments were consumed. context/address refresh from the
     * latest fragments (they're constant per order, but the first pass may see
     * them before the tracker exists).
     */
    public void update(UUID vault, int orderId, InventorySummary leftovers,
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
    public void forget(UUID vault, int orderId) {
        Map<Integer, TrackedOrder> m = orders.get(vault);
        if (m == null) return;
        if (m.remove(orderId) != null) setDirty();
        if (m.isEmpty()) orders.remove(vault);
    }

    /**
     * Two-vault merge: move all tracked orders from the loser UUID to the winner UUID.
     * Called in lockstep with {@link SharedPackagePool#resolveMergeWinner}.
     */
    public void migrateKey(UUID oldId, UUID newId) {
        if (oldId.equals(newId)) return;
        Map<Integer, TrackedOrder> m = orders.remove(oldId);
        if (m == null || m.isEmpty()) return;
        Map<Integer, TrackedOrder> existing = orders.get(newId);
        if (existing == null) {
            orders.put(newId, m);
        } else {
            existing.putAll(m);
        }
        setDirty();
        if (GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-PARTIAL] migrated {} tracked order(s) on vault merge {} -> {}",
                    m.size(), oldId, newId);
        }
    }

    /** No-op merge-participant registration; this tracker only mirrors pool migration. */
    public void noteMergeParticipant(UUID id) {
        // No transient state of its own — SharedPackagePool drives the merge and calls
        // our migrateKey directly. Kept for symmetry with SharedPackagePool's API.
    }

    /**
     * Vault destroyed: drop every tracked order's leftover items as raw item
     * entities (they're unconsumed raw materials, not packages). In-vault
     * fragments of these orders drop separately via vanilla vault breakage —
     * disjoint sets, no double-counting. Idempotent.
     */
    public void drainAndDrop(UUID vault, Level level, BlockPos pos) {
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
