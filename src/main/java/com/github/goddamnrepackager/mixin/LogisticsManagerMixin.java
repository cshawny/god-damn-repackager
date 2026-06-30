package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.google.common.collect.Multimap;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * PROBE-ONLY Mixin (Phase 1).
 *
 * It does NOT change any behaviour. It just observes what
 * {@link LogisticsManager#broadcastPackageRequest} sees and logs it, so we can
 * confirm our understanding of the dispatch flow matches what actually happens
 * in-game before we start rewriting it.
 *
 * Target method signature (from Create 6.0.8 mc1.20.1/dev):
 *   public static boolean broadcastPackageRequest(
 *       UUID freqId, RequestType type, PackageOrderWithCrafts order,
 *       @Nullable IdentifiedInventory ignoredHandler, String address)
 */
@Mixin(value = LogisticsManager.class, remap = false)
public class LogisticsManagerMixin {

    @Inject(
        method = "broadcastPackageRequest",
        at = @At("HEAD"),
        cancellable = false
    )
    private static void goddamnrepacker$logBroadcast(
            UUID freqId,
            LogisticallyLinkedBehaviour.RequestType type,
            PackageOrderWithCrafts order,
            @Nullable Object ignoredHandler,
            String address,
            CallbackInfoReturnable<Boolean> cir) {

        // How many links (packager links) are present on this network right now?
        List<LogisticallyLinkedBehaviour> allLinks =
                LogisticallyLinkedBehaviour.getAllPresent(freqId, true).stream().toList();

        // Breakdown of the incoming order
        List<String> stackDescriptions = order.stacks().stream()
                .map(bis -> bis.count + "x " + ((ItemStack) bis.stack).getItem())
                .toList();

        GodDamnRepacker.LOGGER.info(
                "[GDR-PROBE] broadcastPackageRequest called:\n" +
                "  freqId={}, type={}, address={}\n" +
                "  order items={}\n" +
                "  hasCraftingContext={}\n" +
                "  ALL links on network: {} (these are the packager-links available BEFORE Create dedupes them)",
                freqId, type, address,
                stackDescriptions,
                PackageOrderWithCrafts.hasCraftingInformation(order),
                allLinks.size()
        );

        for (int i = 0; i < allLinks.size(); i++) {
            GodDamnRepacker.LOGGER.info(
                    "[GDR-PROBE]   link[{}] redstonePower={}",
                    i,
                    allLinks.get(i).redstonePower
            );
        }
    }
}
