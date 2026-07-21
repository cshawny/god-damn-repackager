package com.github.goddamnrepackager;

import java.util.UUID;

/**
 * Carrier object passed through Create's
 * {@link com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer} extraData
 * trio ({@code getExtraData}/{@code setExtraData}/{@code modifyExtraData}) so that a
 * vault's stable UUID travels across split / reform / reshape together with the
 * BlockEntity — see {@code ItemVaultBlockEntityMixin} and TECHNICAL.md §3.11.
 *
 * <p>This is a plain {@code record} (immutable, value-equality) carrying at most one
 * {@link UUID}. It flows through Create's {@code Object}-typed channel opaquely: Create
 * only ever passes the instance between the three methods on multiblock reshape, never
 * inspecting its contents, so any non-null {@code Object} subclass works.</p>
 *
 * <p>{@code vaultId} may be {@code null} when a fresh vault has not yet been assigned a
 * UUID; receivers must null-check before using.</p>
 */
public record VaultExtraData(UUID vaultId) {}
