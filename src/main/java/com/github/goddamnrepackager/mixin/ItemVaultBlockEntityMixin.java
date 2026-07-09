package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.SharedPackagePool;
import com.github.goddamnrepackager.VaultBoxTracker;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches vault RESHAPE (add/remove blocks changing geometry without full
 * destruction) and migrates the shared pool key from the old BoundingBox to the
 * new one, so packages aren't orphaned when the vault shape changes.
 *
 * Why this is needed: the existing ConnectivityHandlerMixin injects public
 * splitMulti, which covers break/wrench but NOT reshape — reshape goes through
 * the private splitMultiAndInvalidate directly. notifyMultiUpdated() is the
 * public hook that fires AFTER the new geometry is committed (setWidth/setHeight
 * ran before this call), on both fresh formation and reform/reshape.
 *
 * At HEAD the new radius/length are already set, so boxOf() computes the NEW
 * box. We compare against the last-known box tracked per controller; if they
 * differ, migrate the pool key (preserve packages, FIFO, counts). On first
 * sight (no prior box) we just remember it.
 *
 * notifyMultiUpdated fires once per part during fresh formation too, but only
 * the controller holds real geometry (radius>1 || length>1), and a part's box
 * equals its single-block position — harmless since we key on the controller and
 * only act when isController() holds a real multiblock.
 */
@Mixin(value = ItemVaultBlockEntity.class, remap = false)
public class ItemVaultBlockEntityMixin {

    @Inject(method = "notifyMultiUpdated()V", at = @At("HEAD"))
    private void goddamnrepackager$migratePoolOnReshape(CallbackInfo ci) {
        ItemVaultBlockEntity self = (ItemVaultBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        // Only the controller holds authoritative multiblock geometry.
        if (!self.isController()) return;
        int radius = self.getWidth();
        int length = self.getHeight();
        if (radius <= 1 && length <= 1) return; // not a real multiblock

        MinecraftServer server = level.getServer();
        if (server == null) return;

        BoundingBox newBox = VaultBoxTracker.boxOf(self);
        BoundingBox oldBox = VaultBoxTracker.lastBox(self);
        if (oldBox != null && !oldBox.equals(newBox)) {
            // Reshape: migrate the pool key so packages follow the new geometry.
            SharedPackagePool.get(server).migrateKey(oldBox, newBox);
        }
        VaultBoxTracker.remember(self, newBox);
    }
}
