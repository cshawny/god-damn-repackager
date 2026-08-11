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
 * Detects vault teardown (break / wrench) and drops a 1/N share of the
 * corresponding shared package pool + partial-order tracker as item entities
 * at the broken block's position.
 *
 * <p>Targets the PUBLIC {@code splitMulti(T)} — the universal entry point called by
 * {@code ItemVaultBlock.onRemove} (break) and {@code onWrenched} (wrench top face).
 * The broken part's UUID is read directly off the BE (mixin-injected field), no
 * {@code getControllerBE()} hop — that's the §3.9③ footgun during teardown.</p>
 *
 * <h3>0.5.1 history — partial/full detection (now removed in "方案 A")</h3>
 *
 * <p>0.5.1 originally kept a strict "partial break → don't drain / full teardown →
 * drain all" distinction here, using a neighborhood UUID scan
 * ({@link VaultGeometry#countSiblingsWithUuid}) to decide. The intent was
 * to make reshape "pool follows the UUID, no items drop" — collapsing a partial
 * break back into a single-vault reshape was supposed to be invisible.</p>
 *
 * <p>This file now implements {@code 方案 A}: <b>every block break (partial or full)
 * drains 1/N of the pool and tracker at the broken block's position</b>, where N is
 * the vault's pre-break block count
 * ({@link VaultGeometry#countSiblingsWithUuid} + 1). The drained packages are
 * <b>unpacked to their inner contents</b> (see {@link SharedPackagePool#drainAndDropFraction})
 * rather than dropped as PackageItem shells — a vault being torn down has no
 * recipient to honor, and unpacked items match the vanilla intuition of "vault
 * contents on the ground" without producing an unreasonable number of unfamiliar
 * package entities at one block.</p>
 *
 * <p><b>Player-visible consequence vs the old 0.5.1 behavior:</b> reshape (break +
 * reform) now drops 1/N of the pool as items at the broken block. Players who
 * reshape mid-order will see some crafting output on the ground — they can pick
 * it up and use it. The trade-off was approved in exchange for vault teardown
 * no longer looking "obviously wrong" (one vault block exploding a backlog
 * exceeding its own physical capacity). See TECHNICAL.md §3.11 "方案 A: 1/N drain".</p>
 *
 * <p>Why splitMulti and not splitMultiAndInvalidate: the latter is the deeper
 * chokepoint (also fires for add-block reshape) but it is private static with a
 * package-private SearchCache parameter, and Mixin requires the handler to declare
 * the FULL target parameter list — which would force us to reference the
 * package-private SearchCache type. splitMulti is public and takes only the
 * BlockEntity, so it's cleanly targetable. Reshape-by-placing-a-block (which goes
 * through {@code splitMultiAndInvalidate} + {@code tryToFormNewMultiOfWidth}
 * without ever calling {@code splitMulti}) does <b>not</b> drain — only block
 * break / wrench triggers a drain. See TECHNICAL.md §3.11.</p>
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * Identical to the Forge 0.5.1 方案 A implementation — the {@code splitMulti(T)} public
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

// Determine the vault UUID. Read directly off the broken BE first. If null,
// fall back to the controller BE's UUID — at splitMulti HEAD the controller
// BE is still alive in the chunk (removeBlockEntity was called only on the
// broken block, and brokenVault.getControllerBE() resolves the controller BE
// via the broken BE's still-intact controller field).
//
// Why the fallback matters: in a freshly-formed vault the UUID is minted only
// on the controller via notifyMultiUpdated; the OTHER parts have gdr$vaultId =
// null until a previous splitMulti has propagated setExtraData to them. So
// breaking a non-controller part first would otherwise hit the null early
// return below and skip the drain entirely (regression: first break of a
// non-controller does nothing, contradicting the "every break drops ~1/N"
// design). Falling back to the controller resolves the actual vault key the
// pool is keyed under.
UUID vaultId = ((ItemVaultBlockEntityVaultIdAccessor) brokenVault).goddamnrepackager$getVaultId();
ItemVaultBlockEntity controller = brokenVault.getControllerBE();
if (vaultId == null && controller != null) {
    vaultId = ((ItemVaultBlockEntityVaultIdAccessor) controller).goddamnrepackager$getVaultId();
}
if (vaultId == null) {
    // This vault has never had a UUID minted (no repackager ever queried it,
    // never formed in a multi with notifyMultiUpdated firing on the controller).
    // Nothing in the pool or tracker belongs to it — let vanilla teardown run.
    return;
}
if (controller == null) {
    // Defensive: if vaultId was resolved but controller is null we can't
    // pre-propagate (below) — give up cleanly. Shouldn't happen in practice:
    // either we read UUID off the broken controller itself (controller=brokenVault,
    // non-null), or we read via fallback which required controller != null.
    return;
}

// PROACTIVELY propagate vaultId to all surviving siblings of the SAME vault.
// We do this NOW at splitMulti HEAD because splitMulti's body will subsequently
// call `partAt.setExtraData((partControllerBE == null ? null : partControllerBE.getExtraData()))`
// on each survivor. But when WE just broke the CONTROLLER block, every survivor's
// partControllerBE resolves to null (the controller BE has been removedFromChunk by
// removeBlockEntity at offset 65 of onRemove, before splitMulti at offset 70). That
// makes the setExtraData call a no-op for UUID propagation, and the survivors then
// re-form next tick with notifyMultiUpdated lazy-minting FRESH UUIDs — orphaning
// the pool entry still under the broken vault's old UUID. Pre-propagating here keeps
// the identity chain alive across a controller break.
BlockPos controllerPos = controller.getBlockPos();
VaultGeometry.propagateVaultIdToSiblings(level, brokenPos, controllerPos, vaultId);

// Count surviving siblings to determine N = pre-break vault block count.
// After the proactive propagation above, ALL surviving siblings of this vault
// carry vaultId (those that were already set are untouched), so the UUID match
// in countSiblingsWithUuid correctly returns the survivor count. The broken
// block has already been removed from the chunk by vanilla onRemove
// (dropContents → removeBlockEntity → splitMulti), so it doesn't count itself
// in the scan result. N = siblings + 1 = total blocks right before this break.
// N=1 means we just broke the last block → drainAndDropFraction(1) drains all
// remaining (base = pending, remainder = 0 → deterministic full drain) —
// same as the old full-teardown path.
int siblingCount = VaultGeometry.countSiblingsWithUuid(level, brokenPos, vaultId);
int N = siblingCount + 1;

        // Drain 1/N of the pool + 1/N of every tracked order's leftovers, unpacked
        // to inner item contents and dropped as item entities at brokenPos.
        SharedPackagePool.get(server).drainAndDropFraction(vaultId, level, brokenPos, N);
        PartialOrderTracker.get(server).drainAndDropFraction(vaultId, level, brokenPos, N);
    }
}
