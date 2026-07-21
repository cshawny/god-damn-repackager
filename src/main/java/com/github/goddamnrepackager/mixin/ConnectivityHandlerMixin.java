package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.ItemVaultBlockEntityVaultIdAccessor;
import com.github.goddamnrepackager.PartialOrderTracker;
import com.github.goddamnrepackager.SharedPackagePool;
import com.github.goddamnrepackager.VaultGeometry;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Detects vault teardown (break / wrench) and drains the corresponding shared
 * package pool so packages are dropped instead of being orphaned in SavedData.
 *
 * <p>Targets the PUBLIC {@code splitMulti(T)} — the universal entry point called by
 * {@code ItemVaultBlock.onRemove} (break) and {@code onWrenched} (wrench top face).
 * The broken part's UUID is read directly off the BE (mixin-injected field), no
 * {@code getControllerBE()} hop — that's the §3.9③ footgun during teardown.</p>
 *
 * <h3>0.5.1 change vs 0.5.0</h3>
 *
 * <ul>
 *   <li>Pool key is now the vault's stable UUID, not a geometry-derived
 *       {@code BoundingBox}. Reshape can no longer split/duplicate the pool.</li>
 *   <li><b>Partial-break vs full-teardown detection:</b> vanilla calls splitMulti
 *       for BOTH partial breaks (remaining parts re-form with the same UUID via
 *       extraData, in-vault fragments stay alive, the order keeps crafting) and
 *       full teardown (vault disappears). Draining on a partial break deletes
 *       the pool while the order is still active — the next fragment scan
 *       re-takeovers the order and re-crafts a duplicate batch (the 0.5.1
 *       regression we're fixing here). So we only drain when no sibling vault BE
 *       with the same UUID survives anywhere nearby.</li>
 * </ul>
 *
 * <p>Why splitMulti and not splitMultiAndInvalidate: the latter is the deeper
 * chokepoint (also fires for add-block reshape) but it is private static with a
 * package-private SearchCache parameter, and Mixin requires the handler to declare
 * the FULL target parameter list — which would force us to reference the
 * package-private SearchCache type. splitMulti is public and takes only the
 * BlockEntity, so it's cleanly targetable. Reshape is now handled separately by
 * the UUID mechanism in {@code ItemVaultBlockEntityMixin} — see TECHNICAL.md §3.11.</p>
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * Identical to the Forge 0.5.1 implementation — the {@code splitMulti(T)} public
 * signature is unchanged in 1.21.1 (bytecode-confirmed). No migration adjustments.
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
        if (!(be instanceof ItemVaultBlockEntity brokenVault)) return;

        BlockPos brokenPos = brokenVault.getBlockPos();
        MinecraftServer server = level.getServer();
        if (server == null) return;

        // Read the UUID directly off the broken BE. We deliberately do NOT use
        // getControllerBE() here — during teardown the controller field can already
        // be nulled (§3.9③ footgun), causing getControllerBE to return the part
        // itself with stale width/height=1. Reading the mixed-in UUID off the BE
        // directly is geometry-independent and reliable.
        UUID vaultId = ((ItemVaultBlockEntityVaultIdAccessor) brokenVault).goddamnrepackager$getVaultId();
        if (vaultId == null) {
            // This vault was never used by any repackager (UUID never minted). Nothing
            // in the pool or tracker belongs to it. (PartialOrderTracker might still
            // have a stale entry from a previous incarnation, but that's an orphan we
            // intentionally leave for the legacy-format cleanup to handle.)
            return;
        }

        // CRITICAL: distinguish "full teardown" from "partial break + reform".
        // vanilla calls splitMulti for BOTH:
        //   - full break (every part destroyed) → vault gone → drain
        //   - partial break (one part of a multi-block vault) → remaining parts
        //     RE-FORM with the same UUID (via extraData inheritance), in-vault
        //     fragments stay in the remaining parts, and the order keeps crafting.
        //     Draining here would delete the pool mid-craft, and the next fragment
        //     scan would re-takeover the order and re-craft a duplicate batch —
        //     the 0.5.1 regression.
        //
        // Scan the neighborhood for any surviving vault BE with the same UUID. The
        // broken block has already been removed from the chunk by vanilla onRemove
        // (dropContents → removeBlockEntity → splitMulti), so it won't count. If
        // ANY sibling survives, this is a partial break — leave the pool alone and
        // let the re-formed multiblock inherit it.
        if (VaultGeometry.anySiblingVaultWithUuidExists(level, brokenPos, vaultId)) {
            return;
        }

        // No sibling survives → the multiblock is fully gone. Drain the pool and
        // the tracker so packages and leftover raw materials drop as items.
        SharedPackagePool.get(server).drainAndDrop(vaultId, level, brokenPos);
        PartialOrderTracker.get(server).drainAndDrop(vaultId, level, brokenPos);
    }
}
