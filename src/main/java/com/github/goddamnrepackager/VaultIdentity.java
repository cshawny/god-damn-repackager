package com.github.goddamnrepackager;

import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/**
 * Utility for resolving the stable vault UUID a repackager is attached to.
 *
 * <p>Lives outside the mixin classes because mixin methods get merged into the target
 * class and cannot declare public/static helpers there (§3.9⑤).</p>
 *
 * <p><b>0.5.1 change:</b> was {@code vaultBoundingBoxOf} returning a
 * {@link net.minecraft.world.level.levelgen.structure.BoundingBox} derived from
 * Create's {@code InventoryIdentifier.Bounds}. That BoundingBox was geometry-derived
 * and raced during reshape (see TECHNICAL.md §3.11). Now we resolve the actual vault
 * controller BE through {@link InvManipulationBehaviour#getTarget()} and read its
 * stable mixed-in UUID via {@link ItemVaultBlockEntityVaultIdAccessor}.</p>
 *
 * <p>Returns {@code null} if the repackager has no target inventory, the target isn't
 * a vault, or the controller BE can't be resolved (including the part reporting
 * itself as its own controller, which is unreliable during split/reform/merge).
 * Callers fall back to vanilla behavior in that case (same as 0.5.0).</p>
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * On 1.21.1 {@code InvManipulationBehaviour.getInventory()} / {@code getIdentifiedInventory()}
 * still exist, but {@code IdentifiedInventory} doesn't expose the connected BlockEntity;
 * the geometry-derived {@code InventoryIdentifier} is no longer trustworthy. We instead
 * reverse-resolve the vault BE from {@code getTarget().getConnectedPos()}.
 */
public final class VaultIdentity {

    private VaultIdentity() {}

    /**
     * Resolve the stable UUID of the vault this repackager targets, or null.
     *
     * <p>Path: {@code targetInventory.getTarget()} gives a {@link BlockFace}; its
     * {@code getConnectedPos()} is the vault block the repackager faces. Resolve that
     * to a BE, walk to the controller BE via vanilla {@code getControllerBE()}, then
     * read the mixed-in UUID. The UUID is lazily minted by {@code ItemVaultBlockEntityMixin}
     * on first {@code notifyMultiUpdated} — if the vault is brand-new and that hasn't
     * fired yet, we mint it here on demand.</p>
     *
     * <p><b>controller==null returns null (not part-fallback).</b> A previous version
     * fell back to {@code vault} (the part BE itself) when {@code getControllerBE()}
     * returned null. That was a defensive guard for the §3.9③ footgun during teardown,
     * but it could read a stale UUID off the part itself during the split/reform
     * window of a two-vault merge — the part inherits its origin vault's UUID via
     * extraData, while the pool has already been migrated to the merge winner.
     * Returning null lets all three callers fall back to vanilla cleanly without
     * risk of polling a pool key that's been migrated away. See TECHNICAL.md §3.11
     * "Two-vault merge — transient key divergence".</p>
     */
    public static UUID vaultIdOf(RepackagerBlockEntity r) {
        InvManipulationBehaviour tb = r.targetInventory;
        if (tb == null) return null;
        BlockFace target = tb.getTarget();
        if (target == null) return null;
        BlockPos vaultPos = target.getConnectedPos();
        if (vaultPos == null) return null;
        Level level = r.getLevel();
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(vaultPos);
        if (!(be instanceof ItemVaultBlockEntity vault)) return null;
        ItemVaultBlockEntity controller = vault.getControllerBE();
        if (controller == null) return null; // split/reform window: let callers fall back to vanilla
        ItemVaultBlockEntityVaultIdAccessor acc = (ItemVaultBlockEntityVaultIdAccessor) controller;
        UUID id = acc.goddamnrepackager$getVaultId();
        if (id == null) {
            // Vault was just placed / chunk just loaded and notifyMultiUpdated hasn't
            // fired yet. Mint on demand so deposit/poll can proceed.
            id = UUID.randomUUID();
            acc.goddamnrepackager$setVaultId(id);
            controller.setChanged();
        }
        return id;
    }
}
