package com.architect.gpuchunkgenerator;

import com.architect.gpuchunkgenerator.vulkan.VulkanContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mod("gpuchunkgenerator")
public class GpuChunkGenerator {

    public static final Logger LOGGER = LogUtils.getLogger();

    public GpuChunkGenerator(IEventBus modEventBus) {
        // Register the setup method for modloading
        modEventBus.addListener(this::setup);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("GPU Chunk Generator Initialization Started!");
        
        // Initialisation de Vulkan
        try {
            VulkanContext.getInstance().init();
            
            // Enregistrement du hook de nettoyage
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("GPU Chunk Generator Shutdown Hook Triggered!");
                VulkanContext.getInstance().cleanup();
            }));
            
        } catch (Exception e) {
            LOGGER.error("Échec de l'initialisation de Vulkan", e);
        }
    }
}
