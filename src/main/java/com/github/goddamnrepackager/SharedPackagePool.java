package com.github.goddamnrepackager;

import com.simibubi.create.content.logistics.BigItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-vault shared package pool, stored as world-level SavedData.
 *
 * Architecture shift from 0.3.0: repackaged batches go here (keyed by vault
 * BoundingBox) instead of into each repackager's private queuedExitingPackages.
 * Idle repackagers poll from the pool each tick (see PackagerBlockEntityMixin).
 *
 * Lifetime model (vault-centric): the pool is independent of any BlockEntity.
 * Breaking a repackager does NOT drop the pool (it survives in SavedData; place
 * the repackager back and it resumes). Only when the vault itself is destroyed
 * or reshaped does ConnectivityHandlerMixin drain the pool and drop it as items.
 *
 * count semantics (TECHNICAL.md §3.5): a BigItemStack's count is how many times
 * that package ships, not how many entries. poll() pops one shipment unit at a
 * time (splitting the head entry when count>1); deposit() appends whole entries.
 */
public class SharedPackagePool extends SavedData {

    private static final String DATA_ID = "gdr_shared_package_pool";

    private final Map<BoundingBox, Deque<BigItemStack>> pools = new HashMap<>();

    public SharedPackagePool() {}

    /** Load from NBT. */
    public static SharedPackagePool load(CompoundTag root) {
        SharedPackagePool pool = new SharedPackagePool();
        ListTag vaultList = root.getList("Vaults", Tag.TAG_COMPOUND);
        for (int i = 0; i < vaultList.size(); i++) {
            CompoundTag vaultEntry = vaultList.getCompound(i);
            BoundingBox box = new BoundingBox(
                    vaultEntry.getInt("MinX"), vaultEntry.getInt("MinY"), vaultEntry.getInt("MinZ"),
                    vaultEntry.getInt("MaxX"), vaultEntry.getInt("MaxY"), vaultEntry.getInt("MaxZ"));
            ListTag items = vaultEntry.getList("Packages", Tag.TAG_COMPOUND);
            Deque<BigItemStack> deque = new ArrayDeque<>();
            for (int j = 0; j < items.size(); j++) {
                deque.add(BigItemStack.read(items.getCompound(j)));
            }
            if (!deque.isEmpty()) pool.pools.put(box, deque);
        }
        return pool;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag vaultList = new ListTag();
        for (Map.Entry<BoundingBox, Deque<BigItemStack>> e : pools.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag vaultEntry = new CompoundTag();
            BoundingBox b = e.getKey();
            vaultEntry.putInt("MinX", b.minX());
            vaultEntry.putInt("MinY", b.minY());
            vaultEntry.putInt("MinZ", b.minZ());
            vaultEntry.putInt("MaxX", b.maxX());
            vaultEntry.putInt("MaxY", b.maxY());
            vaultEntry.putInt("MaxZ", b.maxZ());
            ListTag items = new ListTag();
            for (BigItemStack bis : e.getValue()) items.add(bis.write());
            vaultEntry.put("Packages", items);
            vaultList.add(vaultEntry);
        }
        root.put("Vaults", vaultList);
        return root;
    }

    public static SharedPackagePool get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                SharedPackagePool::load, SharedPackagePool::new, DATA_ID);
    }

    /** Winner deposits a whole repackaged batch (to tail, FIFO preserved). */
    public void deposit(BoundingBox vault, List<BigItemStack> batch) {
        if (batch.isEmpty()) return;
        Deque<BigItemStack> deque = pools.computeIfAbsent(vault, k -> new ArrayDeque<>());
        for (BigItemStack bis : batch) if (bis.count > 0) deque.addLast(bis);
        setDirty();
    }

    /**
     * Idle repackager polls one shipment unit (from head). Returns null if empty.
     * If the head entry has count>1, splits off one unit and leaves the remainder.
     */
    public BigItemStack poll(BoundingBox vault) {
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

    /**
     * Migrate a vault's pool from an old BoundingBox key to a new one (reshape).
     * Moves the deque intact (FIFO and counts preserved); does NOT drop — the
     * vault still exists, only its geometry changed. No-op if old key has no pool.
     */
    public void migrateKey(BoundingBox oldBox, BoundingBox newBox) {
        if (oldBox.equals(newBox)) return;
        Deque<BigItemStack> deque = pools.remove(oldBox);
        if (deque == null || deque.isEmpty()) return;
        pools.put(newBox, deque);
        setDirty();
        if (GodDamnRepackager.DEBUG_LOGGING) {
            int n = 0;
            for (BigItemStack bis : deque) n += Math.max(0, bis.count);
            GodDamnRepackager.LOGGER.info(
                    "[GDR-POOL] migrated {} package(s) on vault reshape {} -> {}", n, oldBox, newBox);
        }
    }

    public int pending(BoundingBox vault) {
        Deque<BigItemStack> deque = pools.get(vault);
        if (deque == null) return 0;
        int sum = 0;
        for (BigItemStack bis : deque) sum += Math.max(0, bis.count);
        return sum;
    }

    /**
     * Vault destroyed/reshaped: drain this vault's pool and drop as item entities.
     * Idempotent — safe if the pool was already drained or never existed.
     */
    public void drainAndDrop(BoundingBox vault, Level level, BlockPos pos) {
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
}
