package com.github.goddamnrepackager;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point. The real work happens in the Mixins.
 *
 * The mod id {@code goddamnrepackager} matches the mixin config file
 * {@code goddamnrepackager.mixins.json}, which NeoForge discovers via the
 * {@code [[mixins]]} table in {@code neoforge.mods.toml} (no MixinGradle / manifest
 * attribute on NeoForge — see TECHNICAL.md §3.1).
 */
@Mod(GodDamnRepackager.MOD_ID)
public class GodDamnRepackager {
    public static final String MOD_ID = "goddamnrepackager";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /**
     * Set to true to enable verbose pool logging ([GDR-POOL] lines).
     * Off by default to avoid log spam in normal use. Flip to true when debugging
     * load-balancing behavior.
     */
    public static boolean DEBUG_LOGGING = false;

    /**
     * NeoForge passes the mod event bus and mod container as constructor parameters
     * (the Forge-era {@code FMLJavaModLoadingContext.get().getModEventBus()} escape hatch is
     * discouraged). We keep the params so future hooks (data-gen, registry) are easy to add.
     *
     * NOTE: do NOT call {@code NeoForge.EVENT_BUS.register(this)} unless this class actually
     * declares a {@code @SubscribeEvent} method — NeoForge's event bus throws
     * IllegalArgumentException if register() is called on an object with no listeners
     * (unlike Forge, which only warned). This mod's logic lives entirely in mixins, so there
     * is nothing to subscribe to.
     */
    public GodDamnRepackager(IEventBus modBus, ModContainer modContainer) {
        LOGGER.info("God Damn Repackager loaded (NeoForge). Multiple repackagers, one order — finally.");
    }
}
