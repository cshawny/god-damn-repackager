package com.github.goddamnrepackager;

import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Identity helpers for vault multiblocks. Lives outside mixin classes per §3.9⑤
 * (mixin classes can't host static helpers).
 */
public final class VaultGeometry {

    /**
     * Maximum vault dimension in blocks along any axis. Create's vaults are 3×3×3
     * max (radius up to 3, length up to 9 — see {@code getMaxWidth() == 3} and
     * {@code getMaxLength() == 3*3}). So a vault never extends more than 9 blocks
     * from its controller along its long axis, and never more than 3 in either
     * cross axis. We use 11 as a safe over-approximation for the neighborhood scan.
     */
    private static final int MAX_VAULT_RADIUS = 11;

    private VaultGeometry() {}

/**
 * Count non-removed {@link ItemVaultBlockEntity} near {@code origin} whose
 * mixed-in UUID ({@link ItemVaultBlockEntityVaultIdAccessor}) equals
 * {@code vaultId}. Used to compute the vault's total block count right before
 * each block break: {@code N (pre-break) = count + 1 (the broken block itself)}.
 * {@code count == 0} means we just broke the last block → full teardown.
 *
 * <p><b>Why a neighborhood scan and not getControllerBE?</b> During teardown
 * {@code getControllerBE()} is the §3.9③ footgun — a broken part's controller
 * field may already point at itself, so its {@code getWidth()/getHeight()}
 * return 1, and a bounding-box scan based on those would miss the real
 * multiblock geometry entirely. Instead we just enumerate a cube of positions
 * around the broken part and ask the level what's at each. Vault parts are
 * always physically contiguous, so any surviving part of the same multiblock
 * is within {@code MAX_VAULT_RADIUS} of any other part.
 *
 * <p>The broken block has already been removed from the chunk by the time
 * {@code splitMulti} HEAD fires (vanilla {@code onRemove} order:
 * dropContents → removeBlockEntity → splitMulti), so it won't be counted in
 * the result.</p>
 */
public static int countSiblingsWithUuid(Level level, BlockPos origin, java.util.UUID vaultId) {
    int r = MAX_VAULT_RADIUS;
    int count = 0;
    for (int dx = -r; dx <= r; dx++) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = origin.offset(dx, dy, dz);
                BlockEntity be = level.getBlockEntity(p);
                if (!(be instanceof ItemVaultBlockEntity ivbe)) continue;
                if (be.isRemoved()) continue;
                java.util.UUID id = ((ItemVaultBlockEntityVaultIdAccessor) ivbe).goddamnrepackager$getVaultId();
                if (vaultId.equals(id)) count++;
            }
        }
    }
    return count;
}

/**
 * Set the vault UUID on every non-removed {@link ItemVaultBlockEntity} near {@code origin}
 * that belongs to the same vault (i.e. whose {@code getController()} equals {@code controllerPos})
 * and currently has a null mixed-in UUID. Used proactively from
 * {@code ConnectivityHandlerMixin} at {@code splitMulti} HEAD, BEFORE the body of
 * {@code splitMultiAndInvalidate} runs {@code setExtraData} on each survivor.
 *
 * <p><b>Motivation.</b> When a vanilla {@code onRemove} breaks the CONTROLLER block of a
 * multiblock vault, the surviving parts' setExtraData calls inside the body of
 * {@code splitMultiAndInvalidate} receive {@code null} — because
 * {@code partAt.getControllerBE()} resolves to {@code level.getBlockEntity(controllerPos)},
 * which returns null once the controller BE has been removedFromChunk at offset 65 of
 * {@code ItemVaultBlock.onRemove}. Without this pre-propagation, survivors would re-form
 * next tick with {@code notifyMultiUpdated} lazy-minting FRESH UUIDs — orphaning any pool
 * entry still under the broken vault's old UUID. Pre-setting UUID on all survivors here
 * keeps the identity chain alive across a controller break.</p>
 *
 * <p><b>Why the controller-pos match.</b> At splitMulti HEAD the surviving parts' controller
 * field has NOT yet been reset by removeController (that runs later in splitMulti's body), so
 * {@code ivbe.getController()} reliably points at the same controller BlockPos the broken
 * block referenced. This identifies siblings of the SAME vault regardless of whether they've
 * been UUID-minted/propagated yet — which is exactly the case we're fixing.</p>
 *
 * <p>Parts that already have a non-null UUID (e.g. previously propagated from earlier
 * splitMulti bodies) are left untouched — same {@code if (gdr$vaultId == null)} guard as
 * {@code ItemVaultBlockEntityMixin.setExtraData} uses.</p>
 *
 * <p>The broken block has already been removed from the chunk (removeBlockEntity ran before
 * splitMulti HEAD), so it isn't iterated and won't get its UUID overwritten.</p>
 *
 * @param controllerPos the broken vault's controller position. For a non-controller broken
 *                     part this is {@code brokenVault.getControllerBE().getBlockPos()};
 *                     for the broken controller itself this is its own worldPosition.
 */
public static void propagateVaultIdToSiblings(Level level, BlockPos origin, BlockPos controllerPos, java.util.UUID vaultId) {
    int r = MAX_VAULT_RADIUS;
    for (int dx = -r; dx <= r; dx++) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = origin.offset(dx, dy, dz);
                BlockEntity be = level.getBlockEntity(p);
                if (!(be instanceof ItemVaultBlockEntity ivbe)) continue;
                if (be.isRemoved()) continue;
                BlockPos otherController = ivbe.getController();
                if (otherController == null || !otherController.equals(controllerPos)) continue;
                ItemVaultBlockEntityVaultIdAccessor acc = (ItemVaultBlockEntityVaultIdAccessor) ivbe;
                if (acc.goddamnrepackager$getVaultId() == null) {
                    acc.goddamnrepackager$setVaultId(vaultId);
                    ivbe.setChanged();
                }
            }
        }
    }
}
}
