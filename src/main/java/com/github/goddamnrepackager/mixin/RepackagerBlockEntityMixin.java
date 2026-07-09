package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.GodDamnRepackager;
import com.github.goddamnrepackager.SharedPackagePool;
import com.github.goddamnrepackager.VaultIdentity;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Path ② (0.4.0): shared package pool. The repackager that wins the fragment
 * race would otherwise assemble the ENTIRE order's packages and dump them all
 * into its own queuedExitingPackages — then emit only one per second (CYCLE=20
 * ticks), so a 60-package order takes 60s on one machine even with several
 * sharing the same vault.
 *
 * Instead of splitting the batch across siblings (0.3.0's snapshot distribution),
 * we now deposit the whole batch into a per-vault shared pool (SharedPackagePool,
 * a world-level SavedData keyed by the vault's BoundingBox). Every idle
 * repackager polls the pool each tick (see PackagerBlockEntityMixin) and ships
 * whatever it pulls — so N repackagers genuinely ship N packages/second.
 *
 * Why this replaces 0.3.0's two layers:
 *  - The pool IS the distribution: deposit (here) is the only producer.
 *  - The pool IS the dynamic rebalancing: idle repackagers poll on demand, so a
 *    stalled repackager's backlog is automatically picked up by idle siblings
 *    without any explicit rebalance pass. No snapshot, no threshold tuning.
 *
 * Lifetime (vault-centric): the pool lives in SavedData, independent of any
 * BlockEntity. Breaking a repackager does NOT drop its pool — place it back and
 * it resumes. Only vault teardown/reshape drains the pool
 * (ConnectivityHandlerMixin). See TECHNICAL.md §2.2 (0.4.0) for the full model.
 *
 * Vault identity: the pool key is the vault's BoundingBox, obtained via
 * Create's InventoryIdentifier (for vaults it's Bounds(BoundingBox), a value
 * record keyed on geometry — stable across capability rebuilds; see §3.7). We
 * deliberately do NOT compare raw IItemHandler identity.
 */
@Mixin(value = RepackagerBlockEntity.class, remap = false)
public class RepackagerBlockEntityMixin {

    /**
     * Redirect the {@code queuedExitingPackages.addAll(boxesToExport)} call in
     * attemptToRepackage. Instead of filling the winner's private queue, deposit
     * the whole batch into the per-vault shared pool. The winner then competes
     * equally with its siblings to poll packages back out, one per idle tick.
     *
     * If we can't resolve a vault key (no target inventory / no identifier yet),
     * fall back to vanilla addAll — single-repackager setups behave identically
     * to Create, and so does any repackager whose target isn't a vault.
     */
    @Redirect(
            method = "attemptToRepackage",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z",
                    ordinal = 0
            )
    )
    private boolean goddamnrepackager$depositToPool(
            List<BigItemStack> queue, Collection<BigItemStack> boxesToAdd) {
        RepackagerBlockEntity self = (RepackagerBlockEntity) (Object) this;
        Level level = self.getLevel();
        BoundingBox vaultKey = VaultIdentity.vaultBoundingBoxOf(self);
        if (vaultKey == null || level == null) {
            return queue.addAll(boxesToAdd); // no resolvable vault: vanilla fallback
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return queue.addAll(boxesToAdd);
        }

        List<BigItemStack> batch = new ArrayList<>(boxesToAdd);
        SharedPackagePool.get(server).deposit(vaultKey, batch);

        if (GodDamnRepackager.DEBUG_LOGGING) {
            int total = 0;
            for (BigItemStack bis : batch) total += Math.max(1, bis.count);
            GodDamnRepackager.LOGGER.info(
                    "[GDR-POOL] deposited {} package(s) from {} (vault pending: {})",
                    total, self.getBlockPos().toShortString(),
                    SharedPackagePool.get(server).pending(vaultKey));
        }
        return true;
    }
}
