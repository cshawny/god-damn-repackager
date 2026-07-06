package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parallel repackaging via two complementary mechanisms:
 *
 * Layer 1 — Snapshot distribution (思路 A): the repackager that wins the fragment race would
 * otherwise assemble the ENTIRE order's packages and dump them all into its own
 * queuedExitingPackages. Each repackager emits only one package per second (CYCLE=20 ticks), so a
 * 60-package order takes 60s on one machine even when several share the same vault. We intercept
 * the `queuedExitingPackages.addAll(boxesToExport)` call in attemptToRepackage and instead
 * distribute the batch across ALL sibling repackagers on the same vault, weighted by current
 * queue depth (greedy load balancing — each unit goes to whoever has the shortest queue).
 *
 * Layer 2 — Dynamic rebalancing (思路 B): snapshot distribution is taken at ONE moment; if a
 * repackager later stalls (e.g. its downstream path clogs), its queue grows while siblings go
 * idle, and snapshot allocation can't fix that. So every time a repackager is woken by lazyTick
 * (every 10 ticks ≈ 0.5s) we run a rebalance pass BEFORE its attemptToSend, in two modes:
 *   - STALL RECOVERY (priority): if a sibling is stalled (heldBox holds a package but the shipment
 *     animation has finished → downstream isn't pulling), we aggressively offload everything we
 *     can from its queue TAIL onto the least-loaded non-stalled sibling. Without this a stalled
 *     repackager keeps ~3 packages (1 head-locked + 2 below the conservative threshold) that can
 *     never be redistributed. We do NOT touch the stalled one's heldBox — that in-flight package
 *     ships normally once the downstream is restored.
 *   - CONSERVATIVE BALANCE (normal): if no one is stalled, move a small slice from the deepest
 *     queue's TAIL to the shallowest's TAIL, only when severely imbalanced.
 * This lets idle repackagers dynamically pick up work a stalled sibling can't process, without
 * touching tick()/heldBox-clearing/extractItem (which would risk deadlock — see TECHNICAL.md §3.8).
 *
 * Order-preservation guarantees in the rebalance layer (so we don't scramble shipments):
 *  - We only ever pull from a donor's queue TAIL, never its HEAD — tick() consumes the head
 *    (count--), so the head is left strictly alone and the donor's current send rhythm is intact.
 *    This holds in BOTH modes, including stall recovery (a stalled donor keeps its head; we drain
 *    only the tail).
 *  - In conservative-balance mode we only act when imbalanced (max > 2 AND max - min > 1), and
 *    move only (max - min) / 2 units per pass — a step toward balance, not a full drain.
 *  - Moved packages are appended to the receiver's queue TAIL, preserving FIFO order there too.
 *  - Packages are self-describing (PackageItem carries its own address/orderId NBT); downstream
 *    routing does not depend on which repackager emitted which package first, so even the limited
 *    reordering we introduce cannot corrupt a craft.
 *
 * Why ALL siblings (not just idle ones): adding to queuedExitingPackages is independent of the
 * shipment animation, so a busy repackager mid-shipment can still absorb new packages into its
 * queue; the load balancer naturally gives it less when its queue is already long.
 *
 * Sibling matching: two repackagers are "siblings" when they tap the SAME inventory. We compare
 * Create's InventoryIdentifier (a value-based record — for vaults it's Bounds(BoundingBox), which
 * equals only on the multiblock's min/max corner coordinates). We deliberately do NOT compare the
 * raw IItemHandler by identity (==): the handler instance is rebuilt whenever the vault's forge
 * capability is invalidated (chunk unload/reload, controller change, capability refresh), so two
 * repackagers placed before the mod existed — or whose caches were built at different moments —
 * could hold different generations of the handler and would wrongly fail the == check. The
 * InventoryIdentifier is stable across such rebuilds as long as the vault geometry is unchanged.
 *
 * Safety:
 *  - boxesToExport is split by COUNT (BigItemStack.count is the real shipment count), not by
 *    list element. Disjoint counts across recipients => no dupe.
 *  - The winning repackager (`this`) is always a recipient, so with no siblings behavior is
 *    identical to vanilla (it keeps the whole batch).
 *  - notifyUpdate() is called on every non-self recipient/modified repackager so clients animate.
 *  - A SPLIT MISMATCH guard logs a warning if snapshot assigned != total.
 *  - A REBALANCE MISMATCH guard logs a warning if total pending changes across a rebalance pass
 *    (it must be conserved — we only MOVE packages, never create or destroy).
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

    // ============================================================
    // Layer 2: dynamic rebalancing (思路 B, path ①).
    // See class javadoc. Hooked at the HEAD of attemptToSend, which a repackager
    // runs every lazyTick (10 ticks ≈ 0.5s) in redstone-always-on mode. Before it
    // goes pull fragments / ship, we redistribute packages between siblings' queues
    // so a stalled repackager's backlog can be picked up by idle siblings.
    // We do NOT touch tick()/heldBox/extractItem — that protocol is deadlock-prone
    // to interfere with (see TECHNICAL.md §3.8).
    // ============================================================

    /**
     * Rebalance sibling queues before each attemptToSend. Only fires on the redstone
     * self-polling path (requests == null) — the logistics order-dispatch path
     * (requests != null) is left untouched, as is the client side.
     *
     * ci is never cancelled: vanilla attemptToSend (and thus attemptToRepackage, which
     * still uses the Layer-1 addAll redirect) runs normally afterward. The rebalance
     * only re-owners already-queued packages between siblings; it neither creates nor
     * destroys shipment units, which the REBALANCE MISMATCH guard verifies.
     */
    @Inject(method = "attemptToSend(Ljava/util/List;)V", at = @At("HEAD"))
    private void goddamnrepackager$rebalanceBeforeSend(List<?> requests, CallbackInfo ci) {
        if (requests != null) return; // only the redstone self-poll path (null requests)
        RepackagerBlockEntity self = (RepackagerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;

        List<RepackagerBlockEntity> siblings = findAvailableSiblingRepackagers(self);
        if (siblings.size() <= 1) return; // nothing to rebalance against

        // Guard: total pending across siblings must be conserved by the pass.
        int before = sumAllPending(siblings);

        int moved = rebalanceQueues(self, siblings);

        int after = sumAllPending(siblings);
        if (before != after) {
            GodDamnRepackager.LOGGER.warn(
                    "[GDR-REBAL] MISMATCH before={} after={} — possible item loss!",
                    before, after
            );
        }
        if (moved > 0 && GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-REBAL] pos={} moved {} shipment unit(s) toward balance",
                    self.getBlockPos().toShortString(), moved
            );
        }
    }

    /**
     * One rebalance pass over the sibling set. Two modes:
     *
     * 1. STALL RECOVERY (highest priority): if any sibling is stalled (its downstream
     *    is clogged — heldBox holds a package but the shipment animation has long since
     *    finished, so no one is pulling the package off it), its queue can't drain and
     *    its packages would pile up forever. We aggressively move everything we can
     *    (down to the head, which tick() is mid-decrement on) onto the least-loaded
     *    NON-stalled sibling. This bypasses the conservative threshold below because a
     *    stalled repackager's load is not "normal backlog" — it will never go down on
     *    its own. Without this, a stalled repackager keeps ~3 packages (1 head-locked
     *    + 2 below the conservative threshold) that can't be redistributed.
     *
     * 2. CONSERVATIVE BALANCE (normal case): if no one is stalled, find the deepest
     *    and shallowest queues and, only when the imbalance is severe (max > 2 AND
     *    max - min > 1), move a small slice ((max - min) / 2 units) from donor's TAIL
     *    to receiver's TAIL. The conservative threshold avoids churn every 0.5s.
     *
     * Order preservation (see class javadoc) applies to BOTH modes:
     *  - Donor's HEAD is never touched (tick() is decrementing it) — even a stalled
     *    donor keeps its head; we drain only the tail.
     *  - Receiver gets packages at its TAIL, preserving its FIFO order.
     *
     * @return number of shipment units actually moved (for logging / guard accounting)
     */
    private int rebalanceQueues(RepackagerBlockEntity self, List<RepackagerBlockEntity> siblings) {
        int n = siblings.size();

        // === Mode 1: stall recovery. A stalled repackager can't ship; offload its
        // queue to a sibling that can. We do NOT touch the stalled one's heldBox
        // (that single in-flight package stays with it and ships once its downstream
        // is restored) — we only move queuedExitingPackages entries.
        for (int i = 0; i < n; i++) {
            RepackagerBlockEntity donor = siblings.get(i);
            if (!isStalled(donor)) continue;
            int donorLoad = pendingShipmentCount(donor);
            if (donorLoad <= 1) continue; // only the head-locked entry left; nothing to move

            // Pick the least-loaded sibling that is NOT itself stalled to receive.
            // (A stalled receiver would just re-pile-up; skip them.)
            RepackagerBlockEntity receiver = null;
            int receiverLoad = Integer.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                RepackagerBlockEntity cand = siblings.get(j);
                if (isStalled(cand)) continue;
                int load = pendingShipmentCount(cand);
                if (load < receiverLoad) {
                    receiverLoad = load;
                    receiver = cand;
                }
            }
            if (receiver == null) continue; // every sibling is stalled; nothing we can do

            // Aggressively drain the stalled donor down to its head (1 unit). Spread
            // the moved load across receivers by capping at a sane per-pass amount so a
            // single receiver doesn't get flooded; subsequent lazyTicks continue draining.
            int drainTarget = donorLoad - 1;              // leave the head-locked entry
            // Cap the move so we don't dump a huge backlog onto one receiver in one pass;
            // (maxLoad - minLoad)/2 mirrors the conservative mode's step size, but applied
            // here as an upper bound while still being aggressive vs. the stalled donor.
            int balanceStep = Math.max(1, (donorLoad - receiverLoad) / 2);
            int toMove = Math.min(drainTarget, balanceStep);
            if (toMove <= 0) continue;

            if (GodDamnRepackager.DEBUG_LOGGING) {
                GodDamnRepackager.LOGGER.info(
                        "[GDR-REBAL] pos={} STALLED-DONOR donor={} (load={}) -> receiver={} (load={}), moving {}",
                        self.getBlockPos().toShortString(),
                        donor.getBlockPos().toShortString(), donorLoad,
                        receiver.getBlockPos().toShortString(), receiverLoad, toMove
                );
            }
            return transferPackagesFromTail(self, donor, receiver, toMove);
        }

        // === Mode 2: conservative balance (no one stalled).
        int maxIdx = 0;
        int minIdx = 0;
        for (int i = 1; i < n; i++) {
            int load = pendingShipmentCount(siblings.get(i));
            if (load > pendingShipmentCount(siblings.get(maxIdx))) maxIdx = i;
            if (load < pendingShipmentCount(siblings.get(minIdx))) minIdx = i;
        }
        int maxLoad = pendingShipmentCount(siblings.get(maxIdx));
        int minLoad = pendingShipmentCount(siblings.get(minIdx));

        // Conservative: only act on real backlog AND real imbalance. This avoids
        // repeated micro-moves every 0.5s that would scramble tail order pointlessly.
        if (maxIdx == minIdx) return 0;
        if (maxLoad <= 2) return 0;            // donor isn't actually backlogged
        if (maxLoad - minLoad <= 1) return 0;  // already (near) balanced

        int toMove = (maxLoad - minLoad) / 2;  // step halfway toward balance
        if (toMove <= 0) return 0;

        RepackagerBlockEntity donor = siblings.get(maxIdx);
        RepackagerBlockEntity receiver = siblings.get(minIdx);
        return transferPackagesFromTail(self, donor, receiver, toMove);
    }

    /**
     * A repackager is "stalled" when it has a package in hand (heldBox non-empty) but
     * the shipment animation has already finished (animationTicks == 0). In vanilla,
     * heldBox is cleared passively by the downstream pulling the package via
     * PackagerItemHandler.extractItem (see TECHNICAL.md §3.8). So if heldBox is still
     * non-empty after the animation ended, the downstream is NOT pulling — i.e. the
     * repackager's output path is clogged and its queue will never drain on its own.
     *
     * We use this to trigger aggressive offloading of its queuedExitingPackages to
     * siblings that CAN ship. heldBox itself (the one in-flight package) is left
     * untouched — it will ship normally once the downstream is restored.
     *
     * Both heldBox and animationTicks are public fields on PackagerBlockEntity.
     */
    private boolean isStalled(RepackagerBlockEntity r) {
        return !r.heldBox.isEmpty() && r.animationTicks == 0;
    }

    /**
     * Move up to {@code toMove} shipment units from {@code donor}'s queue TAIL to
     * {@code receiver}'s queue TAIL. Walks the donor's queue from the end backward,
     * splitting the last BigItemStack if it has more units than remain to move.
     *
     * The donor's HEAD element (index 0, the one tick() is currently decrementing)
     * is never the source of a move: as long as there is more than one entry we
     * operate on the tail; if there is exactly one entry we only split it when its
     * count exceeds what we still need (leaving the head entry — and its identity —
     * in place for tick()).
     */
    private int transferPackagesFromTail(
            RepackagerBlockEntity self,
            RepackagerBlockEntity donor,
            RepackagerBlockEntity receiver,
            int toMove
    ) {
        List<BigItemStack> donorQueue = donor.queuedExitingPackages;
        List<BigItemStack> receiverQueue = receiver.queuedExitingPackages;
        int remaining = toMove;
        int moved = 0;

        // Walk from the tail. Stop when we've moved enough or only the head is left
        // AND moving its whole count would empty it (we must not remove the head).
        while (remaining > 0 && !donorQueue.isEmpty()) {
            int lastIdx = donorQueue.size() - 1;
            BigItemStack tail = donorQueue.get(lastIdx);
            int tailCount = Math.max(0, tail.count);

            if (tailCount == 0) {
                // Defensive: drop a zero-count entry if one ended up at the tail.
                donorQueue.remove(lastIdx);
                continue;
            }

            if (tailCount <= remaining) {
                // Whole tail entry moves — unless it is also the HEAD (the only entry),
                // in which case tick() may be mid-decrement on it; preserve its identity
                // by splitting instead of removing. This keeps the donor's send rhythm
                // uninterrupted even in the single-entry case.
                boolean isHead = (lastIdx == 0);
                if (isHead) {
                    // Split: shave off what we still need, keep the head entry in place.
                    tail.count -= remaining;
                    receiverQueue.add(new BigItemStack(tail.stack.copy(), remaining));
                    moved += remaining;
                    remaining = 0;
                } else {
                    donorQueue.remove(lastIdx);
                    receiverQueue.add(tail);
                    moved += tailCount;
                    remaining -= tailCount;
                }
            } else {
                // Tail has more than we need: split. The head entry keeps its identity;
                // we only shave off (remaining) units into a new entry for the receiver.
                tail.count -= remaining;
                receiverQueue.add(new BigItemStack(tail.stack.copy(), remaining));
                moved += remaining;
                remaining = 0;
            }
        }

        if (moved > 0) {
            if (donor != self) donor.notifyUpdate();
            if (receiver != self) receiver.notifyUpdate();
        }
        return moved;
    }

    /** Sum of pendingShipmentCount across all siblings — used by the rebalance conservation guard. */
    private int sumAllPending(List<RepackagerBlockEntity> siblings) {
        int total = 0;
        for (RepackagerBlockEntity r : siblings) total += pendingShipmentCount(r);
        return total;
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
     *   2. Flood-fill from there to discover the whole vault multi-block.
     *   3. For each vault block, check its 6 neighbors for RepackagerBlockEntities, confirming
     *      same-vault membership by InventoryIdentifier equality (see class javadoc).
     * This covers arbitrarily large vaults without a fixed radius.
     *
     * Always includes `self` (the winner) as the first element.
     */
    private List<RepackagerBlockEntity> findAvailableSiblingRepackagers(RepackagerBlockEntity self) {
        List<RepackagerBlockEntity> result = new ArrayList<>();
        result.add(self); // winner always included

        Level level = self.getLevel();
        if (level == null) return result;

        InventoryIdentifier myId = vaultIdentifierOf(self);
        if (myId == null) return result;

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
            return findViaRadiusFallback(self, myId, 2);
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
                // Confirm same vault by InventoryIdentifier equality (guards against a repackager
                // that happens to sit next to the vault but is wired to a different inventory).
                // See class javadoc for why we use value equality here instead of IItemHandler ==.
                if (!Objects.equals(myId, vaultIdentifierOf(sibling))) continue;
                found.add(sibling);
            }
        }
        result.addAll(found);
        // `self` may be duplicated (added at top + in `found`); dedupe preserving order.
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    /** Fallback: scan a cube of given radius around self for sibling repackagers (old approach). */
    private List<RepackagerBlockEntity> findViaRadiusFallback(RepackagerBlockEntity self, InventoryIdentifier myId, int radius) {
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
                    if (!Objects.equals(myId, vaultIdentifierOf(sibling))) continue;
                    result.add(sibling);
                }
            }
        }
        return result;
    }

    /**
     * The stable identity of the inventory a repackager is attached to. Uses Create's
     * {@link IdentifiedInventory#identifier()} rather than the raw IItemHandler, because the
     * handler instance is rebuilt on capability invalidation (see class javadoc) while the
     * InventoryIdentifier (for vaults: Bounds(BoundingBox)) is a stable value keyed on geometry.
     * Returns null if the repackager has no target inventory or no resolvable identifier yet.
     */
    private InventoryIdentifier vaultIdentifierOf(RepackagerBlockEntity r) {
        InvManipulationBehaviour tb = r.targetInventory;
        if (tb == null) return null;
        IdentifiedInventory inv = tb.getIdentifiedInventory();
        if (inv == null) return null;
        return inv.identifier();
    }
}
