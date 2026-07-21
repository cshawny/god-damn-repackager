package com.github.goddamnrepackager;

import com.github.goddamnrepackager.mixin.PackageRepackageHelperInvoker;
import com.simibubi.create.compat.computercraft.events.RepackageEvent;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.repackager.PackageRepackageHelper;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Partial repackaging ("order takeover"): let repackagers start crafting as soon
 * as the arrived fragments afford at least one craft, instead of waiting for
 * vanilla's all-or-nothing isOrderComplete gate.
 *
 * Why the previous attempts failed and how this design avoids it (TECHNICAL.md §4.3):
 *
 *  - Orphan/duplication pitfalls: every output box comes from vanilla's own
 *    repackBasedOnRecipes (partial passes) or repack (final pass) plus addAddress
 *    — byte-identical format to vanilla close-out products. We never hand-write
 *    fragment NBT for outputs, and we never touch inventory NBT directly (only
 *    the public IItemHandler.extractItem API, same call vanilla uses).
 *
 *  - "repack consumes != extract deletes" pitfall: we never trigger repack from
 *    inside addPackageFragment. Our pre-scan runs the FULL slot loop with no
 *    early break (vanilla only breaks when an order COMPLETES — in which case we
 *    defer to vanilla entirely). So the collected fragment set always equals the
 *    set the by-orderId extract loop deletes. Structurally guaranteed.
 *
 * Lifecycle of a taken-over order:
 *    fragments arrive → pre-scan: complete? → vanilla (zero divergence)
 *                     → craftable? → partial pass: repackBasedOnRecipes crafts
 *                       what's affordable → products to SharedPackagePool;
 *                       leftovers + consumed slots to PartialOrderTracker;
 *                       fragments extracted (consumed == deleted)
 *                     → ... more partial passes as fragments arrive ...
 *                     → last fragment consumed (cumulative grid complete):
 *                       FINAL pass — leftovers re-injected as in-memory
 *                       pseudo-fragments, then vanilla repack() closes out the
 *                       order (crafts the rest AND exports leftovers with the
 *                       full order context, exactly like vanilla).
 *
 * Takeover is irreversible per order (consumed fragments leave grid gaps, so
 * vanilla can never complete it) — but completion is guaranteed as long as one
 * redstone-powered repackager keeps ticking: every lazyTick re-runs the
 * pre-scan, and materials (not craft counters) bound the total crafts.
 */
public final class PartialRepackager {

    private PartialRepackager() {}

    /**
     * Called from RepackagerBlockEntityMixin at attemptToRepackage HEAD.
     * Returns true if we handled a partial/final pass (caller must cancel
     * vanilla). Returns false to let vanilla run unchanged — which covers the
     * complete-order path, the non-fragment passthrough path, and the
     * nothing-to-do path (vanilla no-ops there anyway).
     */
    public static boolean tryTakeover(RepackagerBlockEntity self, IItemHandler inv) {
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return false;
        MinecraftServer server = level.getServer();
        if (server == null) return false;
        // Partial repackaging is keyed per vault (tracker + shared pool). No
        // resolvable vault → conservative fallback to vanilla-only behavior.
        UUID vaultKey = VaultIdentity.vaultIdOf(self);
        if (vaultKey == null) return false;
        PackageRepackageHelper helper = self.repackageHelper;
        if (helper == null) return false;

        // ---- Pre-scan: EXACT replica of vanilla's scan loop, zero side effects.
        // helper may hold stale fragments from the previous vanilla call (vanilla
        // clears at ITS start), so clear first. Simulated extracts only.
        helper.clear();
        Map<Integer, List<ItemStack>> fragmentsByOrder = new LinkedHashMap<>();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack extracted = inv.extractItem(slot, 1, true);
            if (extracted.isEmpty()) continue;
            if (!PackageItem.isPackage(extracted)) continue;
            if (!helper.isFragmented(extracted)) return false; // passthrough case → vanilla
            int completed = helper.addPackageFragment(extracted);
            if (completed != -1) return false;                 // complete order → vanilla
            fragmentsByOrder.computeIfAbsent(PackageItem.getOrderId(extracted), k -> new ArrayList<>())
                    .add(extracted);
        }
        if (fragmentsByOrder.isEmpty()) return false;

        // No order completes. Try to take over the first craftable order
        // (one order per call, mirroring vanilla's one-order-per-repack rate).
        for (Map.Entry<Integer, List<ItemStack>> e : fragmentsByOrder.entrySet()) {
            if (tryPartialPass(self, server, level, vaultKey, e.getKey(), e.getValue(), inv, helper))
                return true;
        }
        return false;
    }

    /**
     * Attempt one partial (or final) pass for a single order. Returns true if
     * fragments were consumed and products deposited (caller cancels vanilla).
     */
    private static boolean tryPartialPass(RepackagerBlockEntity self, MinecraftServer server, Level level,
                                          UUID vaultKey, int orderId, List<ItemStack> fragments,
                                          IItemHandler inv, PackageRepackageHelper helper) {
        PartialOrderTracker tracker = PartialOrderTracker.get(server);
        PartialOrderTracker.TrackedOrder tracked = tracker.get(vaultKey, orderId);

        // Address + full order context: prefer fresh fragments, fall back to
        // tracked state (every fragment carries the full PackageOrderWithCrafts).
        String address = tracked != null ? tracked.address : "";
        PackageOrderWithCrafts context = tracked != null ? tracked.context : null;
        for (ItemStack frag : fragments) {
            String a = PackageItem.getAddress(frag);
            if (a != null && !a.isEmpty()) address = a;
            PackageOrderWithCrafts ctx = PackageItem.getOrderContext(frag);
            if (ctx != null) context = ctx;
        }
        if (context == null || context.orderedCrafts().isEmpty()) return false; // no crafts → never take over

        // Pool the new fragments' contents + previously tracked leftovers.
        InventorySummary pool = new InventorySummary();
        for (ItemStack frag : fragments) {
            ItemStackHandler contents = PackageItem.getContents(frag);
            for (int i = 0; i < contents.getSlots(); i++)
                pool.add(contents.getStackInSlot(i));
        }
        if (tracked != null) pool.add(tracked.leftovers);

        if (tracked != null && tracked.wouldComplete(fragments)) {
            // ---- FINAL pass: close out via vanilla repack(). Leftovers go back
            // as in-memory pseudo-fragments so repack pools them together with
            // this pass's real fragments; repack then crafts the remainder AND
            // exports leftovers with the full context — vanilla close-out format.
            for (ItemStack pseudo : buildPseudoFragments(tracked, orderId, address, context))
                helper.addPackageFragment(pseudo);
            List<BigItemStack> boxes;
            try {
                boxes = helper.repack(orderId, level.getRandom());
            } catch (RuntimeException ex) {
                // Defensive: leave fragments + tracker untouched, vanilla no-ops.
                GodDamnRepackager.LOGGER.warn("[GDR-PARTIAL] final repack failed for order {}: {}", orderId, ex.toString());
                return false;
            }
            extractFragments(inv, orderId);
            tracker.forget(vaultKey, orderId);
            deposit(self, server, vaultKey, boxes);
            if (GodDamnRepackager.DEBUG_LOGGING) {
                GodDamnRepackager.LOGGER.info(
                        "[GDR-PARTIAL] final pass for order {} at {}: {} package(s) exported, tracker cleared",
                        orderId, self.getBlockPos().toShortString(), countOf(boxes));
            }
            return true;
        }

        // ---- PARTIAL pass: craft what's affordable right now. repackBasedOnRecipes
        // crafts min(ordered, affordable) per recipe and mutates the pool by
        // consuming exactly those ingredients — the mutated pool IS the leftovers.
        List<BigItemStack> boxes = ((PackageRepackageHelperInvoker) helper)
                .goddamnrepackager$repackBasedOnRecipes(pool, context, address, level.getRandom());
        int total = countOf(boxes);
        if (total == 0) return false; // nothing craftable yet — leave fragments in the vault

        // repackBasedOnRecipes doesn't addAddress (vanilla's repack does it after);
        // do it ourselves for the boxes we actually ship. count<=0 boxes are
        // dropped by SharedPackagePool.deposit — shipping them would duplicate
        // their pattern contents (never consumed from the pool).
        for (BigItemStack b : boxes)
            if (b.count > 0) PackageItem.addAddress(b.stack, address);

        extractFragments(inv, orderId);
        tracker.update(vaultKey, orderId, pool, fragments, context, address);
        deposit(self, server, vaultKey, boxes);
        if (GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-PARTIAL] crafted {} package(s) for order {} at {} ({} fragment(s) consumed)",
                    total, orderId, self.getBlockPos().toShortString(), fragments.size());
        }
        return true;
    }

    /**
     * Exact replica of vanilla's extract loop (attemptToRepackage): delete every
     * fragment of this order from the vault. Because the pre-scan ran the full
     * slot loop with no early break, the collected set equals the deleted set —
     * consumption == deletion, structurally.
     */
    private static void extractFragments(IItemHandler inv, int orderId) {
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack extracted = inv.extractItem(slot, 1, true);
            if (extracted.isEmpty()) continue;
            if (!PackageItem.isPackage(extracted)) continue;
            if (PackageItem.getOrderId(extracted) != orderId) continue;
            inv.extractItem(slot, 1, false);
        }
    }

    /**
     * Pack tracked leftovers into in-memory fragment boxes for the final pass.
     * They are never inserted into the vault — only fed to repack() so its
     * pooling/leftover-export sees them. Slot indices are hygienic only (repack
     * doesn't read slots); each carries the full context + address so repack's
     * close-out boxes get the right order metadata.
     */
    private static List<ItemStack> buildPseudoFragments(PartialOrderTracker.TrackedOrder tracked, int orderId,
                                                        String address, PackageOrderWithCrafts context) {
        List<ItemStack> stacks = new ArrayList<>();
        for (BigItemStack bis : tracked.leftovers.getStacks()) {
            int remaining = Math.max(0, bis.count);
            while (remaining > 0) {
                int n = Math.min(remaining, bis.stack.getMaxStackSize());
                stacks.add(bis.stack.copyWithCount(n));
                remaining -= n;
            }
        }
        List<ItemStack> boxes = new ArrayList<>();
        ItemStackHandler handler = new ItemStackHandler(9);
        int slot = 0;
        for (ItemStack s : stacks) {
            handler.setStackInSlot(slot++, s);
            if (slot >= 9) {
                boxes.add(PackageItem.containing(handler));
                handler = new ItemStackHandler(9);
                slot = 0;
            }
        }
        if (slot > 0) boxes.add(PackageItem.containing(handler));
        for (int i = 0; i < boxes.size(); i++) {
            ItemStack box = boxes.get(i);
            boolean last = i == boxes.size() - 1;
            PackageItem.setOrder(box, orderId, 0, last, i, last, context);
            PackageItem.addAddress(box, address);
        }
        return boxes;
    }

    /** Deposit products into the per-vault shared pool + fire computer events like vanilla. */
    private static void deposit(RepackagerBlockEntity self, MinecraftServer server,
                                UUID vaultKey, List<BigItemStack> boxes) {
        if (self.computerBehaviour != null && self.computerBehaviour.hasAttachedComputer()) {
            for (BigItemStack bis : boxes)
                if (bis.count > 0)
                    self.computerBehaviour.prepareComputerEvent(new RepackageEvent(bis.stack, bis.count));
        }
        SharedPackagePool.get(server).deposit(vaultKey, boxes);
    }

    private static int countOf(List<BigItemStack> boxes) {
        int total = 0;
        for (BigItemStack b : boxes) total += Math.max(0, b.count);
        return total;
    }
}
