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
 * a vault, or the controller BE can't be resolved. Callers fall back to vanilla
 * behavior in that case (same as 0.5.0).</p>
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
        if (controller == null) controller = vault; // footgun guard: parts may report controller==self
        ItemVaultBlockEntityVaultIdAccessor acc = (ItemVaultBlockEntityVaultIdAccessor) controller;
        UUID id = acc.goddamnrepackager$getVaultId();
        if (id == null) {
            // Vault was just placed / chunk just loaded and notifyMultiUpdated hasn't
            // fired yet. Mint on demand so deposit/poll can proceed.
            id = java.util.UUID.randomUUID();
            acc.goddamnrepackager$setVaultId(id);
            controller.setChanged();
        }
        return id;
    }
}
