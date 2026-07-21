package com.github.goddamnrepackager;

import com.mojang.serialization.DataResult;
import com.simibubi.create.content.logistics.BigItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-vault shared package pool, stored as world-level SavedData and keyed by the
 * vault's stable {@link UUID} (see {@code ItemVaultBlockEntityMixin} / TECHNICAL.md §3.11).
 *
 * <h3>Architecture (0.4.0 + 0.5.1 identity fix)</h3>
 *
 * Repackaged batches go here instead of into each repackager's private
 * {@code queuedExitingPackages}. Idle repackagers poll from the pool each tick (see
 * {@code PackagerBlockEntityMixin}).
 *
 * <p><b>0.5.1 change:</b> the key was a {@link net.minecraft.world.level.levelgen.structure.BoundingBox}
 * in 0.4.0–0.5.0, which was geometry-derived and raced during reshape (different
 * repackagers saw different BoundingBoxes from vanilla's lazily-recomputed
 * {@code InventoryIdentifier.Bounds}). The key is now the vault's UUID, which is
 * geometry-independent and propagated through Create's extraData trio — reshape can no
 * longer split or duplicate the pool.</p>
 *
 * <h3>Lifetime model (vault-centric)</h3>
 *
 * The pool is independent of any BlockEntity. Breaking a repackager does NOT drop the
 * pool (it survives in SavedData; place the repackager back and it resumes). Only when
 * the vault itself is destroyed does {@code ConnectivityHandlerMixin} drain the pool
 * and drop it as items. Reshape does <b>not</b> drop — the UUID key is unchanged.
 *
 * <h3>count semantics</h3>
 *
 * A {@link BigItemStack}'s {@code count} is how many times that package ships, not how
 * many entries. {@code poll()} pops one shipment unit at a time (splitting the head
 * entry when {@code count > 1}); {@code deposit()} appends whole entries. See
 * TECHNICAL.md §3.5.
 *
 * <h3>Two-vault merge support</h3>
 *
 * {@link #noteMergeParticipant(UUID)} and {@link #resolveMergeWinner} implement the
 * "loser pool migrates to winner UUID" rule for the rare case of two independent vaults
 * fusing into one. See TECHNICAL.md §3.11 "Two-vault merge" subsection.
 *
 * --- 1.21.1 / NeoForge migration notes ---
 * SavedData API changed: computeIfAbsent now takes a SavedData.Factory<T> + name,
 * and save/load carry a HolderLookup.Provider (needed because ItemStack is
 * data-component based since 1.20.5 and its codec requires registry access).
 * BigItemStack no longer has read(CompoundTag)/write() — we use its Codec
 * directly with NbtOps + the registries-backed serialization context.
 */
public class SharedPackagePool extends SavedData {

    private static final String DATA_ID = "gdr_shared_package_pool";

    private final Map<UUID, Deque<BigItemStack>> pools = new HashMap<>();

    /**
     * Transient merge-participant set: every UUID seen via
     * {@code ItemVaultBlockEntityMixin.setExtraData} during an in-flight reshape/merge.
     * Cleared by the new controller's {@code notifyMultiUpdated} after resolving the
     * merge (single-vault reshape = no-op, two-vault merge = migrate losers). Not
     * persisted. Server-thread-only access — no synchronization needed.
     */
    private final Set<UUID> mergeParticipants = new HashSet<>();

    public SharedPackagePool() {}

    /**
     * Load from NBT. Detects the legacy BoundingBox-keyed format and clears on upgrade.
     *
     * @param registries needed to decode ItemStack components inside each BigItemStack
     */
    public static SharedPackagePool load(CompoundTag root, HolderLookup.Provider registries) {
        SharedPackagePool pool = new SharedPackagePool();
        ListTag vaultList = root.getList("Vaults", Tag.TAG_COMPOUND);
        if (vaultList.isEmpty()) return pool;

        // Legacy-format probe: 0.5.0 wrote MinX/MaxX/etc per vault entry; 0.5.1 writes VaultId.
        CompoundTag sample = vaultList.getCompound(0);
        if (sample.contains("MinX")) {
            // Legacy BoundingBox-keyed data from 0.5.0. User-visible alpha break: we drop
            // these on upgrade (changelog calls this out). In-flight packages are lost;
            // players should let active orders finish before updating.
            int lostVaults = vaultList.size();
            int lostPackages = 0;
            for (int i = 0; i < vaultList.size(); i++) {
                lostPackages += vaultList.getCompound(i).getList("Packages", Tag.TAG_COMPOUND).size();
            }
            GodDamnRepackager.LOGGER.warn(
                    "[GDR-POOL] detected legacy BoundingBox-keyed SavedData ({} vault(s), ~{} package(s)); "
                            + "clearing pool on upgrade to 0.5.1 (alpha break — see changelog)",
                    lostVaults, lostPackages);
            return pool;
        }

        // 0.5.1+ UUID-keyed format.
        for (int i = 0; i < vaultList.size(); i++) {
            CompoundTag vaultEntry = vaultList.getCompound(i);
            if (!vaultEntry.hasUUID("VaultId")) continue;
            UUID id = vaultEntry.getUUID("VaultId");
            ListTag items = vaultEntry.getList("Packages", Tag.TAG_COMPOUND);
            Deque<BigItemStack> deque = new ArrayDeque<>();
            for (int j = 0; j < items.size(); j++) {
                BigItemStack bis = decodeBigItemStack(items.getCompound(j), registries);
                if (bis != null) deque.add(bis);
            }
            if (!deque.isEmpty()) pool.pools.put(id, deque);
        }
        return pool;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag vaultList = new ListTag();
        for (Map.Entry<UUID, Deque<BigItemStack>> e : pools.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag vaultEntry = new CompoundTag();
            vaultEntry.putUUID("VaultId", e.getKey());
            ListTag items = new ListTag();
            for (BigItemStack bis : e.getValue()) {
                CompoundTag encoded = encodeBigItemStack(bis, registries);
                if (encoded != null) items.add(encoded);
            }
            vaultEntry.put("Packages", items);
            vaultList.add(vaultEntry);
        }
        root.put("Vaults", vaultList);
        return root;
    }

    /**
     * Get or create the world-level pool. On 1.21.1, {@link SavedData.Factory} bundles
     * the new-instance supplier and the (Tag, HolderLookup.Provider) deserializer.
     */
    public static SharedPackagePool get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        SharedPackagePool::new,
                        (tag, registries) -> load(tag, registries),
                        null /* DataFixType — none, we handle versioning ourselves */),
                DATA_ID);
    }

    // --- BigItemStack codec helpers ---
    // BigItemStack.CODEC is Codec<BigItemStack>; it composes ItemStack.OPTIONAL_CODEC (which needs
    // registry access for components) so we must run it through a registries-aware serialization context.

    private static BigItemStack decodeBigItemStack(CompoundTag tag, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<BigItemStack> result = BigItemStack.CODEC.parse(ops, tag);
        return result.result().orElse(null);
    }

    private static CompoundTag encodeBigItemStack(BigItemStack bis, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        DataResult<Tag> result = BigItemStack.CODEC.encodeStart(ops, bis);
        Tag tag = result.result().orElse(null);
        return tag instanceof CompoundTag ct ? ct : null;
    }

    /** Winner deposits a whole repackaged batch (to tail, FIFO preserved). */
    public void deposit(UUID vault, List<BigItemStack> batch) {
        if (batch.isEmpty()) return;
        Deque<BigItemStack> deque = pools.computeIfAbsent(vault, k -> new ArrayDeque<>());
        for (BigItemStack bis : batch) if (bis.count > 0) deque.addLast(bis);
        setDirty();
    }

    /**
     * Idle repackager polls one shipment unit (from head). Returns null if empty.
     * If the head entry has count>1, splits off one unit and leaves the remainder.
     */
    public BigItemStack poll(UUID vault) {
        Deque<BigItemStack> deque = pools.get(vault);
        if (deque == null || deque.isEmpty()) return null;
        BigItemStack head = deque.peekFirst();
        if (head.count <= 1) {
            deque.pollFirst();
            if (deque.isEmpty()) pools.remove(vault);
            setDirty();
            if (head.count <= 0) {
                // Defensive: a zero-count entry should never exist, but skip it safely.
                return poll(vault);
            }
            return head;
        }
        head.count--;
        setDirty();
        return new BigItemStack(head.stack.copy(), 1);
    }

    public int pending(UUID vault) {
        Deque<BigItemStack> deque = pools.get(vault);
        if (deque == null) return 0;
        int sum = 0;
        for (BigItemStack bis : deque) sum += Math.max(0, bis.count);
        return sum;
    }

    /**
     * Vault destroyed: drain this vault's pool and drop as item entities.
     * Idempotent — safe if the pool was already drained or never existed.
     */
    public void drainAndDrop(UUID vault, Level level, BlockPos pos) {
        Deque<BigItemStack> deque = pools.remove(vault);
        if (deque == null || deque.isEmpty()) return;
        Vec3 dropPos = Vec3.atCenterOf(pos);
        int dropped = 0;
        for (BigItemStack bis : deque) {
            int n = Math.max(0, bis.count);
            for (int i = 0; i < n; i++) {
                ItemStack stack = bis.stack.copy();
                if (!stack.isEmpty()) {
                    ItemEntity entity = new ItemEntity(level, dropPos.x, dropPos.y + 0.5, dropPos.z, stack);
                    entity.setDefaultPickUpDelay();
                    level.addFreshEntity(entity);
                    dropped++;
                }
            }
        }
        deque.clear();
        setDirty();
        if (dropped > 0 && GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-POOL] drained & dropped {} package(s) at vault {}", dropped, pos);
        }
    }

    // ---------------------------------------------------------------------------
    // Two-vault merge support. See TECHNICAL.md §3.11 "Two-vault merge".
    // ---------------------------------------------------------------------------

    /**
     * Called from {@code ItemVaultBlockEntityMixin.setExtraData} each time a vault BE
     * receives a UUID via Create's extraData channel during an in-flight split/reform.
     * Single-vault reshape registers the same UUID many times (idempotent); two-vault
     * merge registers both UUIDs.
     */
    public void noteMergeParticipant(UUID id) {
        if (id != null) mergeParticipants.add(id);
    }

    /**
     * Called from the new controller's {@code notifyMultiUpdated}. If two or more
     * distinct UUIDs are pending, this is a two-vault merge: pick the deterministic
     * winner (smallest UUID), migrate every other participant's pool entries to the
     * winner, and tell the caller (which will adopt the winning UUID). Returns null
     * for single-vault reshape (no merge in flight, or only one participant).
     *
     * @param tracker same lifecycle tracker, migrated in lockstep with this pool.
     * @return the winning UUID if a merge was resolved, null otherwise.
     */
    public UUID resolveMergeWinner(PartialOrderTracker tracker) {
        if (mergeParticipants.size() < 2) {
            mergeParticipants.clear();
            return null;
        }
        UUID winner = Collections.min(mergeParticipants);
        int migrated = 0;
        for (UUID loser : new ArrayList<>(mergeParticipants)) {
            if (loser.equals(winner)) continue;
            migrated += migrateKey(loser, winner);
            tracker.migrateKey(loser, winner);
        }
        mergeParticipants.clear();
        if (migrated > 0 && GodDamnRepackager.DEBUG_LOGGING) {
            GodDamnRepackager.LOGGER.info(
                    "[GDR-POOL] two-vault merge resolved: winner={}, migrated {} package(s) to winner",
                    winner, migrated);
        }
        return winner;
    }

    /**
     * Move a vault's pool from one UUID to another (used by merge resolution).
     * Appends the old deque to the new key's tail (FIFO: old-first stays first).
     * No-op if old key has no pool.
     *
     * @return number of package entries moved (not package count — entries).
     */
    private int migrateKey(UUID oldId, UUID newId) {
        if (oldId.equals(newId)) return 0;
        Deque<BigItemStack> oldDeque = pools.remove(oldId);
        if (oldDeque == null || oldDeque.isEmpty()) return 0;
        Deque<BigItemStack> newDeque = pools.computeIfAbsent(newId, k -> new ArrayDeque<>());
        int moved = 0;
        for (BigItemStack bis : oldDeque) {
            if (bis.count > 0) {
                newDeque.addLast(bis);
                moved++;
            }
        }
        setDirty();
        return moved;
    }
}
