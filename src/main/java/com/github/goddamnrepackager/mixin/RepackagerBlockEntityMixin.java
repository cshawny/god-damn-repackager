package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * PROBE Mixin (Phase 1) — diagnose why only one repackager in the crafter array works.
 *
 * attemptToSend in RepackagerBlockEntity has these early-return exit points:
 *   L71  if (!heldBox.isEmpty() || animationTicks != 0 || buttonCooldown > 0) return;  // busy animating
 *   L73  if (!queuedExitingPackages.isEmpty()) return;                                  // busy flushing output queue
 *   L77  if (targetInv == null || targetInv instanceof PackagerItemHandler) return;    // no valid target inv
 *   L82  if (heldBox.isEmpty()) return;          // after attemptToRepackage: produced nothing (nothing to grab / fragments incomplete)
 *
 * We log a short tag at HEAD showing the repackager's identity + current busy-state, so from the
 * combined log we can tell:
 *   - which repackagers are being ticked at all
 *   - which exit path each one takes each tick
 *   - therefore WHY the others sit idle (busy flushing? nothing to grab? fragments incomplete?)
 *
 * To keep the log readable we collapse the L71/L73 "busy" cases into a single line at HEAD,
 * and only log the L82 "produced nothing" case separately (that's the diagnostic gold).
 */
@Mixin(value = RepackagerBlockEntity.class, remap = false)
public class RepackagerBlockEntityMixin {

    @Inject(method = "attemptToSend", at = @At("HEAD"))
    private void goddamnrepackager$logHead(List<PackagingRequest> queuedRequests, CallbackInfo ci) {
        RepackagerBlockEntity self = (RepackagerBlockEntity) (Object) this;
        BlockPos pos = self.getBlockPos();

        boolean busy = !self.heldBox.isEmpty() || self.animationTicks != 0 || self.buttonCooldown > 0;
        boolean flushing = !self.queuedExitingPackages.isEmpty();

        if (busy || flushing) {
            // This repackager is occupied — it will early-return. Log compactly (high frequency otherwise).
            GodDamnRepacker.LOGGER.info(
                    "[GDR-REPACK] pos={} BUSY (heldBox.empty={} animTicks={} queue={})",
                    pos.toShortString(),
                    self.heldBox.isEmpty(),
                    self.animationTicks,
                    self.queuedExitingPackages.size()
            );
        } else {
            // This repackager is FREE and will actually try to pull from the vault this tick.
            GodDamnRepacker.LOGGER.info(
                    "[GDR-REPACK] pos={} FREE — attempting to pull from vault",
                    pos.toShortString()
            );
        }
    }

    /**
     * Inject right after attemptToRepackage returns, to see whether it produced a box.
     * In the source, L81-82:  if (heldBox.isEmpty()) return;
     * We place the injection at the value load of heldBox for that check (INVOKE on isEmpty),
     * so we capture the "produced nothing" outcome — the key signal for the bottleneck.
     *
     * Because Repackager.attemptToSend is short and the heldBox.isEmpty() at L81 is the only
     * such call AFTER attemptToRepackage, targeting it by ordinal is fragile; instead we log
     * at the TAIL of the whole method only when heldBox is still empty AND we were FREE on entry.
     * That captures "tried but produced nothing" without fragile injection points.
     */
    @Inject(method = "attemptToSend", at = @At("RETURN"))
    private void goddamnrepacker$logReturn(List<PackagingRequest> queuedRequests, CallbackInfo ci) {
        RepackagerBlockEntity self = (RepackagerBlockEntity) (Object) this;
        boolean busy = !self.heldBox.isEmpty() || self.animationTicks != 0 || self.buttonCooldown > 0;
        boolean flushing = !self.queuedExitingPackages.isEmpty();
        // Only log "produced nothing" when the repackager was free to try (not busy/flushing) and still
        // came up empty — that's the idle-with-no-work signal.
        if (!busy && !flushing && self.heldBox.isEmpty()) {
            GodDamnRepacker.LOGGER.info(
                    "[GDR-REPACK] pos={} TRIED BUT PRODUCED NOTHING (vault empty or fragments incomplete)",
                    self.getBlockPos().toShortString()
            );
        }
    }
}
