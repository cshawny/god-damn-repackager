package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.SharedPackagePool;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects vault teardown (break / wrench) and drains the corresponding shared
 * package pool so packages are dropped instead of being orphaned in SavedData.
 *
 * Targets the PUBLIC splitMulti(T) — the universal entry point called by
 * ItemVaultBlock.onRemove (break) and onWrenched (wrench). At HEAD the
 * controller still holds valid OLD geometry (radius/length/worldPosition), so
 * we reconstruct the old BoundingBox using the exact formula from
 * ItemVaultBlockEntity.initCapability().
 *
 * Why splitMulti and not splitMultiAndInvalidate: the latter is the deeper
 * chokepoint (also fires for add-block reshape) but it is private static with a
 * package-private SearchCache parameter, and Mixin requires the handler to
 * declare the FULL target parameter list — which would force us to reference
 * the package-private SearchCache type (not importable from outside Create's
 * package). splitMulti is public and takes only the BlockEntity, so it's
 * cleanly targetable.
 *
 * TRADEOFF: this does NOT catch the add-block reshape path (that calls
 * splitMultiAndInvalidate directly, bypassing splitMulti). So reshaping a vault
 * (adding/removing blocks without fully destroying it) will NOT drop the old
 * pool — the old BoundingBox key becomes orphaned. This is a known first-version
 * limitation; break/wrench (the common teardown paths) are fully covered. A
 * future version could track the last-known box per controller and reconcile on
 * notifyMultiUpdated() to also handle reshape.
 */
@Mixin(value = ConnectivityHandler.class, remap = false)
public class ConnectivityHandlerMixin {

    @Inject(
            method = "splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("HEAD")
    )
    private static void goddamnrepackager$onVaultSplit(BlockEntity be, CallbackInfo ci) {
        if (be == null) return;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        // be may be a non-controller part; resolve the real controller BEFORE
        // splitMulti delegates to splitMultiAndInvalidate (at HEAD controller
        // field is still valid). Use getControllerBE() — NOT isController()
        // (footgun: returns true once controller is nulled during teardown).
        BlockEntity controller;
        if (be instanceof IMultiBlockEntityContainer imbec) {
            controller = imbec.getControllerBE();
        } else {
            controller = be;
        }
        if (!(controller instanceof ItemVaultBlockEntity vault)) return;

        int radius = vault.getWidth();
        int length = vault.getHeight();
        if (radius <= 1 && length <= 1) return; // not a real multiblock

        BlockPos pos = vault.getBlockPos();
        Direction.Axis axis = vault.getMainConnectionAxis(); // X or Z for vaults
        // Exact formula from ItemVaultBlockEntity.initCapability():
        BlockPos farCorner = (axis == Direction.Axis.Z)
                ? pos.offset(radius, radius, length)
                : pos.offset(length, radius, radius);
        BoundingBox oldBox = BoundingBox.fromCorners(pos, farCorner);

        MinecraftServer server = level.getServer();
        if (server == null) return;

        // Idempotent: safe if the pool was already drained or never existed.
        SharedPackagePool.get(server).drainAndDrop(oldBox, level, pos);
        // Drop the tracked last-known box so a future reshape on a re-formed
        // vault at this position doesn't migrate from a stale baseline.
        com.github.goddamnrepackager.VaultBoxTracker.forget(vault);
    }
}
