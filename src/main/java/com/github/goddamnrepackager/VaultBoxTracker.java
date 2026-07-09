package com.github.goddamnrepackager;

import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.WeakHashMap;

/**
 * Tracks the last-known vault BoundingBox per controller BlockEntity, so that on
 * reshape (vault geometry change) the shared pool key can be migrated from the
 * old box to the new box. Create itself does not retain the old BoundingBox
 * after reshape — the setters overwrite radius/length before notifyMultiUpdated
 * fires — so we must remember it ourselves.
 *
 * WeakHashMap auto-cleans entries when the controller BE is GC'd. The map is
 * keyed on the controller BE (resolved via getControllerBE()), not on parts.
 */
public final class VaultBoxTracker {

    private static final WeakHashMap<ItemVaultBlockEntity, BoundingBox> LAST_BOX = new WeakHashMap<>();

    private VaultBoxTracker() {}

    /**
     * Reconstruct the vault's current BoundingBox from its committed geometry,
     * using the exact formula from ItemVaultBlockEntity.initCapability().
     */
    public static BoundingBox boxOf(ItemVaultBlockEntity vault) {
        BlockPos pos = vault.getBlockPos();
        int radius = vault.getWidth();
        int length = vault.getHeight();
        Direction.Axis axis = vault.getMainConnectionAxis(); // X or Z for vaults
        BlockPos farCorner = (axis == Direction.Axis.Z)
                ? pos.offset(radius, radius, length)
                : pos.offset(length, radius, radius);
        return BoundingBox.fromCorners(pos, farCorner);
    }

    /** The last BoundingBox recorded for this controller, or null if none yet. */
    public static BoundingBox lastBox(ItemVaultBlockEntity controller) {
        return LAST_BOX.get(controller);
    }

    /** Record the current box as the baseline for detecting future reshapes. */
    public static void remember(ItemVaultBlockEntity controller, BoundingBox box) {
        LAST_BOX.put(controller, box);
    }

    /** Drop the tracked entry (called on vault teardown). */
    public static void forget(ItemVaultBlockEntity controller) {
        LAST_BOX.remove(controller);
    }
}
