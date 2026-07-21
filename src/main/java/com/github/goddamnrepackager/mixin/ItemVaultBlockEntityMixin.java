package com.github.goddamnrepackager.mixin;

import com.github.goddamnrepackager.ItemVaultBlockEntityVaultIdAccessor;
import com.github.goddamnrepackager.PartialOrderTracker;
import com.github.goddamnrepackager.SharedPackagePool;
import com.github.goddamnrepackager.VaultExtraData;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <p><b>0.5.1: vault identity switched from BoundingBox to UUID.</b> This mixin owns
 * the per-vault UUID and carries it through Create's
 * {@link com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer} extraData
 * trio so that the UUID survives split / reform / reshape. The pool key is the UUID,
 * not the BoundingBox, so reshape never orphans or duplicates pool entries (the bug
 * that 0.5.0 had — see TECHNICAL.md §3.11 for the full story).</p>
 *
 * <h3>How the UUID is propagated</h3>
 *
 * Create calls the extraData trio at the exact moments where identity would otherwise
 * be lost (bytecode-confirmed, see TECHNICAL.md §3.11):
 * <ul>
 *   <li>{@code ConnectivityHandler.splitMultiAndInvalidate} (offset 339-369): for each
 *       part, {@code partControllerBE.getExtraData()} is read and then
 *       {@code part.setExtraData(extraData)} is called <b>before</b>
 *       {@code removeController(true)}. So every part (including the future new
 *       controller) inherits the OLD controller's UUID.</li>
 *   <li>{@code ConnectivityHandler.tryToFormNewMultiOfWidth} (offset 665-1074): after
 *       reform, {@code self.notifyMultiUpdated()} fires on the new controller.</li>
 * </ul>
 *
 * <h3>Two-vault merge (rare but real)</h3>
 *
 * When two independent vaults A and B merge, {@code splitMultiAndInvalidate} runs on
 * each part with its OWN old controller, so parts from vault A inherit uuidA and parts
 * from vault B inherit uuidB. The new controller (chosen by min-coords) sees only the
 * UUID inherited via its own {@code setExtraData}, not the other vault's. To close this
 * gap, {@code setExtraData} also registers each seen UUID into a <b>global
 * merge-participant set</b> (see {@link SharedPackagePool#noteMergeParticipant}). On
 * the next {@code notifyMultiUpdated}, the new controller scans that set and migrates
 * every loser UUID's pool/tracker entries to the winner UUID (deterministically:
 * smallest UUID wins). Single-vault reshape sees only one UUID, so this path is a
 * no-op there.
 *
 * <h3>Lifecycle</h3>
 *
 * The UUID is lazily minted on first need (either {@code notifyMultiUpdated} or
 * queried by a repackager via the accessor interface). It is persisted in NBT under
 * {@code "GDR_VaultId"} so it survives chunk unload / restart / {@code /clone}. When
 * every part of a vault is broken, the UUID dies with the last BE; the existing
 * {@code ConnectivityHandlerMixin.splitMulti} drain (which now reads this UUID) drops
 * any leftover pool contents as items — same safety net as before.
 *
 * <h3>Mixin note on extraData overrides</h3>
 *
 * {@code getExtraData}/{@code setExtraData}/{@code modifyExtraData} are default methods
 * on {@code IMultiBlockEntityContainer} and {@code ItemVaultBlockEntity} does NOT
 * override them. Declaring them in this mixin causes the merged target class to gain
 * concrete overrides of those interface methods — a standard mixin pattern. The method
 * names MUST exactly match the interface method names (no {@code goddamnrepackager$}
 * prefix) so the JVM treats them as interface implementations.
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * The write/read methods on {@code ItemVaultBlockEntity} take a {@link HolderLookup.Provider}
 * in addition to the {@link CompoundTag} + boolean. The {@code @Inject} descriptors and
 * handler signatures below match the 3-arg 1.21.1 form.
 */
@Mixin(value = ItemVaultBlockEntity.class, remap = false)
public class ItemVaultBlockEntityMixin implements ItemVaultBlockEntityVaultIdAccessor {

    /** Stable per-vault UUID. Lazily minted, NBT-persisted. */
    @Unique
    private UUID gdr$vaultId;

    /**
     * Public bridge methods — exposed via the {@code ItemVaultBlockEntityVaultIdAccessor}
     * mixin interface so non-mixin code can read/write the UUID by casting any
     * {@code ItemVaultBlockEntity} to that interface. (Cannot use {@code @Accessor}:
     * Mixin APT can't see fields that are themselves injected by another mixin — see
     * {@link ItemVaultBlockEntityVaultIdAccessor} javadoc.) Unannotated: they satisfy
     * the interface contract via Mixin's duck-typing merge.
     */
    public UUID goddamnrepackager$getVaultId() {
        return gdr$vaultId;
    }

    public void goddamnrepackager$setVaultId(UUID id) {
        gdr$vaultId = id;
    }

    /**
     * UUIDs observed via {@code setExtraData} on this BE since the last
     * {@code notifyMultiUpdated}. Always single-element in normal reshape; multi-element
     * only in two-vault merge. Transient — not persisted; cleared after each
     * {@code notifyMultiUpdated} resolves merges.
     */
    @Unique
    private final Set<UUID> gdr$observedIds = new HashSet<>();

    // ---------------------------------------------------------------------------
    // extraData trio overrides — see class javadoc for the propagation contract.
    // ---------------------------------------------------------------------------

    /** Returns this BE's UUID wrapped in a {@link VaultExtraData} carrier (may be null). */
    public Object getExtraData() {
        return new VaultExtraData(gdr$vaultId);
    }

    /**
     * Receives extraData from Create (called on each part before removeController during
     * split, and on self after reform). Records the incoming UUID for merge detection
     * and adopts it as this BE's UUID if none is set yet.
     */
    public void setExtraData(Object data) {
        if (!(data instanceof VaultExtraData ved)) return;
        UUID incoming = ved.vaultId();
        if (incoming == null) return;
        gdr$observedIds.add(incoming);
        if (gdr$vaultId == null) gdr$vaultId = incoming;
        // Also register globally so a future new-controller notifyMultiUpdated can see
        // UUIDs that landed on sibling parts (not just on this BE). See class javadoc.
        ItemVaultBlockEntity self = (ItemVaultBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        SharedPackagePool.get(server).noteMergeParticipant(incoming);
        PartialOrderTracker.get(server).noteMergeParticipant(incoming);
    }

    /**
     * Merge accumulator. Create calls this on {@code self} (the new controller) once per
     * non-self part during reform, in {@code acc = self.modifyExtraData(acc)} form.
     * For our purposes the accumulator carries no mergeable state (UUID is fixed on
     * self), so we return it unchanged.
     */
    public Object modifyExtraData(Object data) {
        return data;
    }

    // ---------------------------------------------------------------------------
    // notifyMultiUpdated: where merges resolve and lazy UUIDs are minted.
    // ---------------------------------------------------------------------------

    @Inject(method = "notifyMultiUpdated()V", at = @At("HEAD"))
    private void goddamnrepackager$onNotifyMultiUpdated(CallbackInfo ci) {
        ItemVaultBlockEntity self = (ItemVaultBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) return;
        // vanilla calls notifyMultiUpdated on every part during reshape; only the
        // controller's UUID minting / merge resolution matters. isController() returns
        // true either genuinely (controller field == worldPos) or as a footgun
        // (controller field null after removeController, §3.9③) — both are fine here
        // because the UUID logic is geometry-independent.
        if (!self.isController()) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        SharedPackagePool pool = SharedPackagePool.get(server);
        PartialOrderTracker tracker = PartialOrderTracker.get(server);

        // Lazily mint a UUID if this controller has none (first formation / first query).
        if (gdr$vaultId == null) {
            gdr$vaultId = UUID.randomUUID();
            self.setChanged();
        }

        // Resolve a two-vault merge if one is in flight. The global merge-participant
        // set was populated by setExtraData on every part during splitMultiAndInvalidate.
        // Pick winner = smallest UUID (deterministic); migrate all losers to it.
        UUID winner = pool.resolveMergeWinner(tracker);
        if (winner != null && !winner.equals(gdr$vaultId)) {
            // A merge was detected and the deterministic winner differs from our current
            // UUID — adopt the winner so this controller's UUID matches the migrated key.
            gdr$vaultId = winner;
            self.setChanged();
        }

        gdr$observedIds.clear();
    }

    // ---------------------------------------------------------------------------
    // NBT persistence for gdr$vaultId.
    // ---------------------------------------------------------------------------

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("RETURN"))
    private void goddamnrepackager$writeVaultId(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (gdr$vaultId != null) tag.putUUID("GDR_VaultId", gdr$vaultId);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", at = @At("RETURN"))
    private void goddamnrepackager$readVaultId(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.hasUUID("GDR_VaultId")) gdr$vaultId = tag.getUUID("GDR_VaultId");
    }
}
