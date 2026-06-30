package com.github.goddamnrepackager;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point. The real work happens in the Mixins.
 *
 * The mod id {@code goddamnrepackager} matches the mixin config file
 * {@code goddamnrepackager.mixins.json} (registered via the org.spongepowered.mixin Gradle plugin).
 */
@Mod(GodDamnRepackager.MOD_ID)
public class GodDamnRepackager {
    public static final String MOD_ID = "goddamnrepackager";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public GodDamnRepackager() {
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("God Damn Repackager loaded. Multiple repackagers, one order — finally.");
    }
}
