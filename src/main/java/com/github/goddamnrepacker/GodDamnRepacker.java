package com.github.goddamnrepacker;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point. For now it does almost nothing — the real work happens in the Mixins.
 *
 * The mod id {@code goddamnrepacker} matches the mixin config file
 * {@code goddamnrepacker.mixins.json} declared in the jar manifest and in mods.toml.
 */
@Mod(GodDamnRepacker.MOD_ID)
public class GodDamnRepacker {
    public static final String MOD_ID = "goddamnrepacker";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public GodDamnRepacker() {
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        // We register on the FORGE bus, but currently we have no event handlers — everything is Mixin-driven.
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("God Damn Repacker loaded. Multiple packagers, one order — finally.");
    }
}
