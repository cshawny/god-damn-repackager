package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * PHASE 2 Mixin — parallel repackaging via load balancing.
 *
 * Bottleneck (probe-confirmed): the repackager that wins the fragment race assembles the ENTIRE
 * order's packages and dumps them all into its own queuedExitingPackages. Each repackager emits
 * only one package per second (CYCLE=20 ticks), so a 60-package order takes 60s on one machine
 * even when several share the same vault.
 *
 * Fix: intercept the `queuedExitingPackages.addAll(boxesToExport)` call (L134 of
 * attemptToRepackage). Instead of giving the whole batch to `this`, distribute it across
 * all IDLE sibling repackagers attached to the same vault. N idle repackagers => ~N× throughput.
 *
 * Safety:
 *  - Only hand packages to repackagers that are fully idle (heldBox empty, queue empty,
 *    animationTicks==0). A busy one keeps nothing, so its in-flight shipment is untouched.
 *  - We partition boxesToExport into disjoint sublists — each package lives in exactly one
 *    recipient's queue. No package is duplicated (no dupe).
 *  - The winning repackager (`this`) is always included as a recipient, so if no siblings
 *    are idle, behavior is identical to vanilla (it keeps the whole batch).
 *  - notifyUpdate() is called on every non-self recipient so clients animate correctly.
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

        // Find all idle sibling repackagers on the same vault (including self).
        List<RepackagerBlockEntity> recipients = findIdleSiblingRepackagers(self);
        int n = recipients.size();

        GodDamnRepackager.LOGGER.info(
                "[GDR-DIST] pos={} distributing {} shipments ({} distinct stacks) across {} idle repackager(s)",
                selfPos.toShortString(), totalPackages, batch.size(), n
        );

        if (n <= 1) {
            // No idle siblings — vanilla behavior: keep the whole batch on self.
            return queue.addAll(batch);
        }

        // Split each BigItemStack's count across recipients.
        // For a stack with count C across N recipients: recipient i gets a copy with count = C/N
        // (plus 1 for the first C%N recipients to absorb remainder). Disjoint counts => no dupe.
        for (BigItemStack bis : batch) {
            int total = Math.max(1, bis.count);
            int base = total / n;
            int remainder = total % n;
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                int share = base + (i < remainder ? 1 : 0);
                if (share <= 0) continue;
                RepackagerBlockEntity recipient = recipients.get(i);
                // Copy the stack with the recipient's share of the count.
                BigItemStack shareStack = new BigItemStack(bis.stack.copy(), share);
                recipient.queuedExitingPackages.add(shareStack);
                if (recipient != self) {
                    recipient.notifyUpdate();
                }
                assigned += share;
                GodDamnRepackager.LOGGER.info(
                        "[GDR-DIST]   -> pos={} gets {}x of a stack",
                        recipient.getBlockPos().toShortString(), share
                );
            }
            // Sanity: assigned should equal total. If not, something's wrong — log it.
            if (assigned != total) {
                GodDamnRepackager.LOGGER.warn(
                        "[GDR-DIST] SPLIT MISMATCH: assigned={} total={} — possible item loss!",
                        assigned, total
                );
            }
        }

        // We added directly to each recipient's queue above (NOT via `queue.addAll`).
        // Return true to mimic addAll returning "changed".
        return true;
    }

    /**
     * Find all RepackagerBlockEntities that:
     *  - are attached to the SAME inventory the winner repackager is attached to (same vault), AND
     *  - are currently idle (heldBox empty, queuedExitingPackages empty, not animating).
     * Always includes `self` (the winner) as the first element.
     *
     * Strategy (robust against multi-block vaults & unknown facing):
     *  1. Get self's target IItemHandler (the vault's shared handler) via targetInventory.getInventory().
     *  2. Scan a small cube (radius 2) around self for other RepackagerBlockEntities.
     *  3. For each candidate, compare ITS targetInventory.getInventory() to self's by object identity
     *     (==). Multi-block vaults share ONE handler instance, so identity match == same vault.
     *  4. Include candidates that are idle.
     *
     * This avoids the fragile direction/BlockFace arithmetic that previously missed one sibling.
     */
    private List<RepackagerBlockEntity> findIdleSiblingRepackagers(RepackagerBlockEntity self) {
        List<RepackagerBlockEntity> result = new ArrayList<>();
        result.add(self); // winner always included

        Level level = self.getLevel();
        if (level == null) return result;

        InvManipulationBehaviour myTb = self.targetInventory;
        if (myTb == null) return result;
        IItemHandler myInv = myTb.getInventory();
        if (myInv == null) return result;

        BlockPos origin = self.getBlockPos();
        int radius = 2; // vault multi-block can be up to 3x3; siblings sit within 2 blocks

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (p.equals(origin)) continue; // skip self's own position
                    BlockEntity be = level.getBlockEntity(p);
                    if (!(be instanceof RepackagerBlockEntity sibling)) continue;
                    if (sibling == self) continue;

                    // Same vault? compare target handler by identity.
                    InvManipulationBehaviour sibTb = sibling.targetInventory;
                    if (sibTb == null) continue;
                    IItemHandler sibInv = sibTb.getInventory();
                    if (sibInv != myInv) continue; // different inventory => not a sibling

                    if (isIdle(sibling)) {
                        result.add(sibling);
                    }
                }
            }
        }
        return result;
    }

    private boolean isIdle(RepackagerBlockEntity r) {
        return r.heldBox.isEmpty()
            && r.queuedExitingPackages.isEmpty()
            && r.animationTicks == 0;
    }
}
