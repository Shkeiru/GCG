package com.architect.gpuchunkgenerator.vulkan;

import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;

/**
 * Conteneur pour un buffer Vulkan et sa mémoire associée.
 */
public record VulkanBuffer(long bufferHandle, long memoryHandle, long size) {

    /**
     * Détruit le buffer et libère la mémoire associée.
     * @param device Le device logique Vulkan.
     */
    public void destroy(VkDevice device) {
        if (bufferHandle != 0) {
            vkDestroyBuffer(device, bufferHandle, null);
        }
        if (memoryHandle != 0) {
            vkFreeMemory(device, memoryHandle, null);
        }
    }
}
