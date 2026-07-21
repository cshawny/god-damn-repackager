package com.github.goddamnrepackager;

import java.util.UUID;

/**
 * Duck-typing interface for reading/writing the {@code gdr$vaultId} field that
 * {@code com.github.goddamnrepackager.mixin.ItemVaultBlockEntityMixin} injects into
 * {@code ItemVaultBlockEntity}.
 *
 * <p><b>Why not {@code @Accessor} on a {@code @Mixin interface}?</b> Mixin's APT
 * cannot resolve {@code @Accessor} targets that are themselves injected by another
 * mixin in the same compilation (the field doesn't exist in the original class at
 * APT time). We instead use the standard "duck interface" pattern: this is a plain
 * interface (no {@code @Mixin}), and the mixin class declares
 * {@code implements ItemVaultBlockEntityVaultIdAccessor}. Mixin then makes the merged
 * {@code ItemVaultBlockEntity} class implement this interface, and any code can
 * {@code cast} a vault BE to it and call the bridge methods.</p>
 *
 * <p><b>Lives in the root package</b> (not {@code com.github.goddamnrepackager.mixin})
 * because the mixin subsystem forbids direct references to classes inside any
 * registered {@code @Mixin} package — see the runtime error
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be
 * referenced directly} (Mixin rejects any direct class load from a registered mixin
 * package).</p>
 *
 * <p>The method signatures here MUST exactly match the public methods declared in
 * the mixin class so the JVM's interface dispatch finds them on the merged target.</p>
 */
public interface ItemVaultBlockEntityVaultIdAccessor {

    UUID goddamnrepackager$getVaultId();

    void goddamnrepackager$setVaultId(UUID id);
}
