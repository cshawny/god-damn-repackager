package com.github.goddamnrepackager;

import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Utility for resolving the stable vault identity a repackager is attached to.
 * Lives outside the mixin classes because mixin methods get merged into the
 * target class and cannot declare public/static helpers there.
 *
 * Returns the vault's {@link BoundingBox} (obtained from Create's
 * {@link InventoryIdentifier.Bounds}), which is a value-based key stable across
 * capability rebuilds (see TECHNICAL.md §3.7). Returns null if the repackager
 * has no target inventory, no resolvable identifier, or a non-vault identifier.
 */
public final class VaultIdentity {

    private VaultIdentity() {}

    public static BoundingBox vaultBoundingBoxOf(RepackagerBlockEntity r) {
        InvManipulationBehaviour tb = r.targetInventory;
        if (tb == null) return null;
        IdentifiedInventory inv = tb.getIdentifiedInventory();
        if (inv == null) return null;
        InventoryIdentifier id = inv.identifier();
        if (id instanceof InventoryIdentifier.Bounds bounds) return bounds.bounds();
        return null;
    }
}
