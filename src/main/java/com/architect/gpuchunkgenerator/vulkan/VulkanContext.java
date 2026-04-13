package com.architect.gpuchunkgenerator.vulkan;

import com.architect.gpuchunkgenerator.vulkan.ComputePipeline;
import com.architect.gpuchunkgenerator.vulkan.ShaderCompiler;
import com.architect.gpuchunkgenerator.vulkan.VulkanBuffer;
import com.architect.gpuchunkgenerator.vulkan.MemoryAllocator;
import com.architect.gpuchunkgenerator.vulkan.CommandManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Gestionnaire du contexte Vulkan Headless.
 */
public class VulkanContext {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static VulkanContext instance;

    private VkInstance vkInstance;
    private VkDevice vkDevice;
    private VkPhysicalDevice physicalDevice;
    private VkQueue computeQueue;
    private int computeQueueFamilyIndex = -1;

    private CommandManager commandManager;
    private MemoryAllocator allocator;

    private VulkanContext() {}

    public static VulkanContext getInstance() {
        if (instance == null) {
            instance = new VulkanContext();
        }
        return instance;
    }

    public void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createInstance(stack);
            pickPhysicalDevice(stack);
            createLogicalDevice(stack);

            // Initialisation de la pipeline avec un shader de démarrage minimal (3 Bindings)
            String bootShader = """
                #version 450
                layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
                layout(set = 0, binding = 0, std430) writeonly buffer Out { float d[]; };
                layout(set = 0, binding = 1, std430) readonly buffer N { int p[]; };
                layout(set = 0, binding = 2, std430) readonly buffer S { float l[]; };
                void main() {}
                """;
            
            ByteBuffer spirvCode = ShaderCompiler.compileShader(bootShader, Shaderc.shaderc_compute_shader);
            
            ComputePipeline pipeline = new ComputePipeline(this, spirvCode);
            this.allocator = new MemoryAllocator(this);
            this.commandManager = new CommandManager(this, pipeline, allocator);
            
            LOGGER.info("Système initialisé (Boot Shader) et prêt pour la génération JIT.");
        }
    }

    private String loadShaderSource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader non trouvé : " + path);
            Scanner scanner = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (IOException e) {
            throw new RuntimeException("Erreur de lecture du shader", e);
        }
    }

    private void createInstance(MemoryStack stack) {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("GPU Chunk Generator"))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8("No Engine"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK_API_VERSION_1_0);

        // Vérification de la couche de validation
        PointerBuffer layers = null;
        if (isLayerAvailable("VK_LAYER_KHRONOS_validation")) {
            layers = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            LOGGER.info("Couche de validation détectée et activée.");
        } else {
            LOGGER.info("Attention : VK_LAYER_KHRONOS_validation non trouvée.");
        }

        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(layers);

        PointerBuffer pInstance = stack.mallocPointer(1);
        int result = vkCreateInstance(createInfo, null, pInstance);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Erreur lors de la création de l'instance Vulkan : " + result);
        }

        vkInstance = new VkInstance(pInstance.get(0), createInfo);
        LOGGER.info("Instance créée avec succès.");
    }

    private boolean isLayerAvailable(String layerName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer countBuf = stack.ints(0);
            vkEnumerateInstanceLayerProperties(countBuf, null);
            VkLayerProperties.Buffer properties = VkLayerProperties.malloc(countBuf.get(0), stack);
            vkEnumerateInstanceLayerProperties(countBuf, properties);
            for (VkLayerProperties prop : properties) {
                if (prop.layerNameString().equals(layerName)) return true;
            }
            return false;
        }
    }

    private void pickPhysicalDevice(MemoryStack stack) {
        IntBuffer pDeviceCount = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, null);

        if (pDeviceCount.get(0) == 0) {
            throw new RuntimeException("Aucun GPU supportant Vulkan n'a été trouvé.");
        }

        PointerBuffer pPhysicalDevices = stack.mallocPointer(pDeviceCount.get(0));
        vkEnumeratePhysicalDevices(vkInstance, pDeviceCount, pPhysicalDevices);

        for (int i = 0; i < pDeviceCount.get(0); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(pPhysicalDevices.get(i), vkInstance);
            if (isDeviceSuitable(device, stack)) {
                physicalDevice = device;
                break;
            }
        }

        if (physicalDevice == null) {
            throw new RuntimeException("Aucun GPU ne possède de famille de queue supportant COMPUTE.");
        }

        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(physicalDevice, properties);
        LOGGER.info("GPU sélectionné : {}", properties.deviceNameString());
    }

    private boolean isDeviceSuitable(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer pQueueFamilyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, null);

        VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(pQueueFamilyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, pQueueFamilyCount, queueFamilies);

        for (int i = 0; i < pQueueFamilyCount.get(0); i++) {
            if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                computeQueueFamilyIndex = i;
                return true;
            }
        }

        return false;
    }

    private void createLogicalDevice(MemoryStack stack) {
        FloatBuffer pQueuePriorities = stack.floats(1.0f);

        VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeQueueFamilyIndex)
                .pQueuePriorities(pQueuePriorities);

        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos);

        PointerBuffer pDevice = stack.mallocPointer(1);
        int result = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Erreur lors de la création du device logique : " + result);
        }

        vkDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(vkDevice, computeQueueFamilyIndex, 0, pQueue);
        computeQueue = new VkQueue(pQueue.get(0), vkDevice);

        LOGGER.info("Device logique et Queue de calcul créés.");
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * Recharge la pipeline de calcul avec un nouveau bytecode SPIR-V.
     * Détruit l'ancienne pipeline pour éviter les fuites de VRAM.
     */
    public void reloadPipeline(ByteBuffer newSpirv) {
        if (commandManager == null) {
            throw new IllegalStateException("Le CommandManager n'est pas initialisé.");
        }

        LOGGER.info("Rechargement de la pipeline...");
        
        // 1. Récupération et destruction de l'ancienne pipeline
        ComputePipeline oldPipeline = commandManager.getComputePipeline();
        if (oldPipeline != null) {
            oldPipeline.destroy();
            LOGGER.info("Ancienne pipeline détruite.");
        }

        // 2. Création de la nouvelle pipeline
        ComputePipeline newPipeline = new ComputePipeline(this, newSpirv);
        
        // 3. Mise à jour du CommandManager
        commandManager.setPipeline(newPipeline);
        
        LOGGER.info("Nouvelle pipeline installée avec succès.");
    }

    public VkDevice getDevice() {
        return vkDevice;
    }

    public VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public VkQueue getComputeQueue() {
        return computeQueue;
    }

    public int getComputeQueueFamilyIndex() {
        return computeQueueFamilyIndex;
    }

    public void uploadNoisePermutations(java.util.List<byte[]> octaves) {
        if (commandManager != null) {
            commandManager.uploadNoisePermutations(octaves);
        }
    }

    public void uploadNoiseParameters(java.util.List<com.architect.gpuchunkgenerator.ast.ir.GpuNode.Noise> noises) {
        if (commandManager != null) {
            commandManager.uploadNoiseParameters(noises);
        }
    }

    /* LUT Upload removed in favor of functional splines */

    /**
     * Libère les ressources Vulkan.
     */
    public void cleanup() {
        if (commandManager != null) {
            commandManager.cleanup();
        }
        if (vkDevice != null) {
            vkDestroyDevice(vkDevice, null);
        }
        if (vkInstance != null) {
            vkDestroyInstance(vkInstance, null);
        }
        LOGGER.info("Ressources libérées.");
    }
}
