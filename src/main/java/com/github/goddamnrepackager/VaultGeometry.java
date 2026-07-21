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
     * Is there any non-removed {@link ItemVaultBlockEntity} near {@code origin}
     * whose mixed-in UUID ({@link ItemVaultBlockEntityVaultIdAccessor}) equals
     * {@code vaultId}? Used to distinguish "the whole multiblock is gone" (drain
     * the pool) from "this was a partial break, the remaining parts re-formed and
     * inherited the same UUID" (keep the pool).
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
     * dropContents → removeBlockEntity → splitMulti), so it won't be counted.
     * We only need ONE surviving BE with the same UUID to know the multiblock
     * (or a re-formed remnant) is still alive.</p>
     */
    public static boolean anySiblingVaultWithUuidExists(Level level, BlockPos origin, java.util.UUID vaultId) {
        int r = MAX_VAULT_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (!(be instanceof ItemVaultBlockEntity ivbe)) continue;
                    if (be.isRemoved()) continue;
                    java.util.UUID id = ((ItemVaultBlockEntityVaultIdAccessor) ivbe).goddamnrepackager$getVaultId();
                    if (vaultId.equals(id)) return true;
                }
            }
        }
        return false;
    }
}
