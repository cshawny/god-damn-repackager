package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parallel repackaging via load-balanced distribution (思路 A).
 *
 * Bottleneck (probe-confirmed): the repackager that wins the fragment race assembles the ENTIRE
 * order's packages and dumps them all into its own queuedExitingPackages. Each repackager emits
 * only one package per second (CYCLE=20 ticks), so a 60-package order takes 60s on one machine
 * even when several share the same vault.
 *
 * Fix: intercept the `queuedExitingPackages.addAll(boxesToExport)` call (L134 of
 * attemptToRepackage). Instead of giving the whole batch to `this`, distribute it across ALL
 * sibling repackagers attached to the same vault, weighted by current queue depth (greedy
 * load balancing — each unit goes to whoever has the shortest queue).
 *
 * Why ALL siblings (not just idle ones): adding to queuedExitingPackages is independent of the
 * shipment animation, so a busy repackager mid-shipment can still absorb new packages into its
 * queue; the load balancer naturally gives it less when its queue is already long.
 *
 * Safety:
 *  - boxesToExport is split by COUNT (BigItemStack.count is the real shipment count), not by
 *    list element. Disjoint counts across recipients => no dupe.
 *  - The winning repackager (`this`) is always a recipient, so with no siblings behavior is
 *    identical to vanilla (it keeps the whole batch).
 *  - notifyUpdate() is called on every non-self recipient so clients animate correctly.
 *  - A SPLIT MISMATCH guard logs a warning if assigned != total (would indicate a real bug).
 */
@Mixin(value = RepackagerBlockEntity.class, remap = false)
public class RepackagerBlockEntityMixin {

    /**
     * Redirect the `queuedExitingPackages.addAll(boxesToExport)` call.
     *
     * KEY INSIGHT: boxesToExport is a List<BigItemStack>, but each BigItemStack's `count` field
     * is the REAL number of times that package must be shipped (tick() decrements count, only
     * removes the entry when count hits 0). So a 60-item order is ONE BigItemStack(count=60),
     * not 60 entries. To parallelize, we must split each BigItemStack's COUNT across recipients,
     * not the list elements.
     *
     * @param queue      the receiver — this repackager's queuedExitingPackages list
     * @param boxesToAdd the freshly assembled packages (each BigItemStack may have count>1)
     * @return true to mimic addAll's contract (batch consumed)
     */
    @Redirect(
        method = "attemptToRepackage",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z",
            ordinal = 0
        )
    )
    private boolean goddamnrepackager$distributeAcrossRepackagers(List<BigItemStack> queue, Collection<BigItemStack> boxesToAdd) {
        RepackagerBlockEntity self = (RepackagerBlockEntity) (Object) this;
        BlockPos selfPos = self.getBlockPos();

        // Materialize the incoming batch.
        List<BigItemStack> batch = new ArrayList<>(boxesToAdd);
        if (batch.isEmpty()) {
            return queue.addAll(batch); // nothing to do, behave vanilla
        }

        // Total shipment count = sum of each BigItemStack.count (the real package count).
        int totalPackages = 0;
        for (BigItemStack bis : batch) totalPackages += Math.max(1, bis.count);

        // Find all sibling repackagers on the same vault that are AVAILABLE to receive work.
        // "Available" = not currently mid-shipment animation (heldBox empty, animationTicks==0).
        // Unlike the old isIdle() check, a non-empty queue is OK — we balance by load below,
        // so a nearly-empty queue (busy sibling about to finish) can still get new work.
        List<RepackagerBlockEntity> recipients = findAvailableSiblingRepackagers(self);
        int n = recipients.size();

        if (n <= 1) {
            if (GodDamnRepackager.DEBUG_LOGGING) {
                GodDamnRepackager.LOGGER.info(
                        "[GDR-DIST] pos={} keeping {} shipments (no available siblings)",
                        selfPos.toShortString(), totalPackages
                );
            }
            return queue.addAll(batch);
        }

        if (GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-DIST] pos={} distributing {} shipments ({} distinct stacks) across {} repackager(s)",
                    selfPos.toShortString(), totalPackages, batch.size(), n
            );
        }

        // === Load-balanced split ===
        // Each recipient's "weight" = its current pending shipment count (queuedExitingPackages).
        // We allocate the new packages to minimize the MAX queue depth across recipients after
        // assignment — i.e., pour new packages onto whoever has the shortest queue first.
        // This naturally lets a busy-but-soon-idle repackager (short queue) grab new work.
        int[] currentLoad = new int[n];
        for (int i = 0; i < n; i++) {
            currentLoad[i] = pendingShipmentCount(recipients.get(i));
        }

        for (BigItemStack bis : batch) {
            int total = Math.max(1, bis.count);
            int[] share = new int[n];
            // Greedy: assign each of the `total` units to whichever recipient currently has
            // the lowest (currentLoad + share). Ties broken by index order (stable).
            for (int u = 0; u < total; u++) {
                int best = 0;
                for (int i = 1; i < n; i++) {
                    if (currentLoad[i] + share[i] < currentLoad[best] + share[best]) {
                        best = i;
                    }
                }
                share[best]++;
            }

            // Hand out each recipient's share.
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                if (share[i] <= 0) continue;
                RepackagerBlockEntity recipient = recipients.get(i);
                BigItemStack shareStack = new BigItemStack(bis.stack.copy(), share[i]);
                recipient.queuedExitingPackages.add(shareStack);
                currentLoad[i] += share[i];
                if (recipient != self) {
                    recipient.notifyUpdate();
                }
                assigned += share[i];
            }
            // Log this stack's split (only in debug mode).
            if (GodDamnRepackager.DEBUG_LOGGING) {
                StringBuilder splitLog = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    if (share[i] > 0) {
                        if (splitLog.length() > 0) splitLog.append(", ");
                        splitLog.append(recipients.get(i).getBlockPos().toShortString())
                                .append("=").append(share[i]);
                    }
                }
                GodDamnRepackager.LOGGER.info(
                        "[GDR-DIST]   stack({}x) split: {}",
                        total, splitLog
                );
            }
            if (assigned != total) {
                GodDamnRepackager.LOGGER.warn(
                        "[GDR-DIST] SPLIT MISMATCH: assigned={} total={} — possible item loss!",
                        assigned, total
                );
            }
        }

        return true;
    }

    /** Sum of counts in a repackager's queuedExitingPackages = how many packages it still owes. */
    private int pendingShipmentCount(RepackagerBlockEntity r) {
        int sum = 0;
        for (BigItemStack bis : r.queuedExitingPackages) sum += Math.max(0, bis.count);
        return sum;
    }

    /**
     * Find all RepackagerBlockEntities on the same vault. ALL of them are candidates — we do NOT
     * exclude busy/animating ones, because adding to queuedExitingPackages is independent of the
     * shipment animation: a repackager mid-shipment can still absorb new packages into its queue,
     * and the tick() loop will ship them later. The load-balanced split (favoring shorter queues)
     * decides who gets what.
     *
     * Strategy: repackagers sit on the OUTSIDE of the vault multi-block. They can be attached to
     * any of the vault's constituent blocks, so a small radius around `self` may miss siblings on
     * the far side of a large vault. To find ALL of them reliably:
     *   1. Find the vault block `self` is attached to (its target inventory position).
     *   2. Flood-fill from there to discover the whole vault multi-block (same IItemHandler identity).
     *   3. For each vault block, check its 6 neighbors for RepackagerBlockEntities.
     * This covers arbitrarily large vaults without a fixed radius.
     *
     * Always includes `self` (the winner) as the first element.
     */
    private List<RepackagerBlockEntity> findAvailableSiblingRepackagers(RepackagerBlockEntity self) {
        List<RepackagerBlockEntity> result = new ArrayList<>();
        result.add(self); // winner always included

        Level level = self.getLevel();
        if (level == null) return result;

        InvManipulationBehaviour myTb = self.targetInventory;
        if (myTb == null) return result;
        IItemHandler myInv = myTb.getInventory();
        if (myInv == null) return result;

        // Step 1: find the vault block self is attached to.
        // The repackager's connecting face points AT the vault; the vault block is one step in
        // that direction. We try each of the 6 directions and pick the one whose BlockEntity's
        // inventory (if any) shares our IItemHandler identity.
        BlockPos selfPos = self.getBlockPos();
        BlockPos vaultStart = null;
        for (Direction d : Direction.values()) {
            BlockPos candidate = selfPos.relative(d);
            BlockEntity be = level.getBlockEntity(candidate);
            if (be == null) continue;
            // Does this block expose the same handler we're attached to? We can't easily query an
            // arbitrary BE's handler, so instead we check: is it a PackagerBlockEntity? No — vaults
            // are not packagers. We just need any non-repackager BE as a flood-fill seed.
            if (!(be instanceof RepackagerBlockEntity)) {
                vaultStart = candidate;
                break;
            }
        }
        if (vaultStart == null) {
            // Couldn't find the vault block; fall back to small-radius scan around self.
            return findViaRadiusFallback(self, myInv, 2);
        }

        // Step 2: flood-fill the vault multi-block from vaultStart, bounded by a sane max size.
        // We treat a position as part of the vault if its BlockEntity is NOT a RepackagerBlockEntity
        // (vaults/crates/chests are not repackagers). We cap exploration to avoid runaway scans.
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(vaultStart);
        int maxVaultBlocks = 200; // a 5x5x8 vault is 200; plenty of headroom
        int scanned = 0;

        while (!frontier.isEmpty() && visited.size() < maxVaultBlocks) {
            BlockPos cur = frontier.poll();
            if (!visited.add(cur)) continue;
            scanned++;
            if (scanned > maxVaultBlocks * 6) break; // hard cap on total iterations

            BlockEntity be = level.getBlockEntity(cur);
            // Vault constituent blocks are block entities with inventory (Vault, Crate, etc.)
            // but NOT repackagers. Air/empty stops the flood.
            if (be == null) continue;
            if (be instanceof RepackagerBlockEntity) continue; // don't flood into siblings

            // This is a vault block. Check its 6 neighbors for repackager siblings.
            for (Direction d : Direction.values()) {
                BlockPos neighbor = cur.relative(d);
                // Enqueue for continued flood-fill (vault body).
                if (!visited.contains(neighbor)) frontier.add(neighbor);
            }
        }

        // Step 3: for each discovered vault block, scan its neighbors for repackager siblings.
        Set<RepackagerBlockEntity> found = new LinkedHashSet<>();
        found.add(self);
        for (BlockPos vb : visited) {
            for (Direction d : Direction.values()) {
                BlockPos siblingPos = vb.relative(d);
                if (siblingPos.equals(selfPos)) continue;
                BlockEntity be = level.getBlockEntity(siblingPos);
                if (!(be instanceof RepackagerBlockEntity sibling)) continue;
                if (sibling == self) continue;
                // Confirm same vault via handler identity (guards against a repackager that happens
                // to sit next to the vault but is wired to a different inventory).
                InvManipulationBehaviour sibTb = sibling.targetInventory;
                if (sibTb == null) continue;
                IItemHandler sibInv = sibTb.getInventory();
                if (sibInv != myInv) continue;
                found.add(sibling);
            }
        }
        result.addAll(found);
        // `self` may be duplicated (added at top + in `found`); dedupe preserving order.
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    /** Fallback: scan a cube of given radius around self for sibling repackagers (old approach). */
    private List<RepackagerBlockEntity> findViaRadiusFallback(RepackagerBlockEntity self, IItemHandler myInv, int radius) {
        List<RepackagerBlockEntity> result = new ArrayList<>();
        result.add(self);
        Level level = self.getLevel();
        if (level == null) return result;
        BlockPos origin = self.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (p.equals(origin)) continue;
                    BlockEntity be = level.getBlockEntity(p);
                    if (!(be instanceof RepackagerBlockEntity sibling)) continue;
                    if (sibling == self) continue;
                    InvManipulationBehaviour sibTb = sibling.targetInventory;
                    if (sibTb == null) continue;
                    if (sibTb.getInventory() != myInv) continue;
                    result.add(sibling);
                }
            }
        }
        return result;
    }
}
