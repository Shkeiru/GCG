package com.architect.gpuchunkgenerator;

import com.architect.gpuchunkgenerator.vulkan.VulkanContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("gpuchunkgenerator")
public class GpuChunkGenerator {

    public GpuChunkGenerator(IEventBus modEventBus) {
        // Register the setup method for modloading
        modEventBus.addListener(this::setup);
    }

    private void setup(final FMLCommonSetupEvent event) {
        System.out.println("GPU Chunk Generator Initialization Started!");
        
        // Initialisation de Vulkan
        try {
            VulkanContext.getInstance().init();
            
            // Enregistrement du hook de nettoyage
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("GPU Chunk Generator Shutdown Hook Triggered!");
                VulkanContext.getInstance().cleanup();
            }));
            
        } catch (Exception e) {
            System.err.println("[Vulkan] Échec de l'initialisation : " + e.getMessage());
        }
    }
}
