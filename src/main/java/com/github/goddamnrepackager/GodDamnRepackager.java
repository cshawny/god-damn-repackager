package com.github.goddamnrepackager;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
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
     * discouraged). We don't currently subscribe to any mod-bus events, but we keep the
     * params so future hooks (data-gen, registry) are easy to add.
     */
    public GodDamnRepackager(IEventBus modBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("God Damn Repackager loaded (NeoForge). Multiple repackagers, one order — finally.");
    }
}
