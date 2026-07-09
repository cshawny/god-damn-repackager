package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.github.goddamnrepackager.SharedPackagePool;
import com.github.goddamnrepackager.VaultIdentity;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Strategy A: feed the pool into each idle repackager's private queue.
 *
 * Must target PackagerBlockEntity (not RepackagerBlockEntity) because
 * Repackager does NOT override tick() — it inherits the parent's. The
 * instanceof guard ensures we only touch repackagers, never plain packagers.
 *
 * Safety: we only add one package to the private queue when it is empty AND
 * heldBox is empty AND animationTicks==0. vanilla tick() then dequeues it from
 * the head next. The private queue stays at 0~1 elements, so vanilla sees no
 * difference; the heldBox passive-clear protocol (TECHNICAL.md §3.8) is untouched.
 * A stalled repackager (heldBox non-empty) is naturally blocked by the
 * !heldBox.isEmpty() guard and receives no new packages — it just waits for its
 * downstream to clear, satisfying the "stop feeding on stall" policy.
 */
@Mixin(value = PackagerBlockEntity.class, remap = false)
public class PackagerBlockEntityMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void goddamnrepackager$feedFromPool(CallbackInfo ci) {
        PackagerBlockEntity self = (PackagerBlockEntity) (Object) this;
        if (!(self instanceof RepackagerBlockEntity repackager)) return; // plain packagers untouched

        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;

        // Respect vanilla redstone gate. vanilla tick() has no redstone check
        // itself — it only drains queuedExitingPackages, which vanilla only
        // fills via attemptToSend (itself gated by lazyTick's
        // `if (!redstonePowered) return`). Our pool-feed writes directly to the
        // queue from tick HEAD, bypassing that gate, so we must re-apply it here:
        // a Repackager must be redstone-powered to pull from the shared pool.
        // redstonePowered is the public field vanilla's lazyTick GATE 1 reads.
        if (!self.redstonePowered) return;

        // Only feed when truly idle and the private queue is drained.
        if (self.animationTicks != 0) return;
        if (!self.heldBox.isEmpty()) return;                // stalled: don't feed
        if (!self.queuedExitingPackages.isEmpty()) return;  // still digesting

        BoundingBox vaultKey = VaultIdentity.vaultBoundingBoxOf(repackager);
        if (vaultKey == null) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        BigItemStack pkg = SharedPackagePool.get(server).poll(vaultKey);
        if (pkg != null) {
            self.queuedExitingPackages.add(pkg);
            if (GodDamnRepackager.DEBUG_LOGGING) {
                GodDamnRepackager.LOGGER.info(
                        "[GDR-POOL] fed 1 package to repackager at {} (vault pending: {})",
                        self.getBlockPos().toShortString(),
                        SharedPackagePool.get(server).pending(vaultKey));
            }
        }
    }
}
