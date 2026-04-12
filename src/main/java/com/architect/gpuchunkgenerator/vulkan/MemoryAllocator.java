package com.architect.gpuchunkgenerator.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Utilitaire d'allocation mémoire pour Vulkan.
 */
public class MemoryAllocator {

    private final VulkanContext context;

    public MemoryAllocator(VulkanContext context) {
        this.context = context;
    }

    /**
     * Trouve un type de mémoire approprié sur le GPU.
     */
    public int findMemoryType(int typeFilter, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(context.getPhysicalDevice(), memProperties);

            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes().get(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        throw new RuntimeException("Impossible de trouver un type de mémoire compatible !");
    }

    /**
     * Crée un buffer Vulkan et alloue sa mémoire.
     */
    public VulkanBuffer createBuffer(long size, int usage, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Création du handle du buffer
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(context.getDevice(), bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec de la création du buffer Vulkan : " + result);
            }
            long bufferHandle = pBuffer.get(0);

            // 2. Récupération des besoins mémoire
            VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(context.getDevice(), bufferHandle, memRequirements);

            // 3. Allocation de la mémoire physique
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memRequirements.size())
                    .memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), properties));

            LongBuffer pBufferMemory = stack.mallocLong(1);
            result = vkAllocateMemory(context.getDevice(), allocInfo, null, pBufferMemory);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec de l'allocation mémoire GPU : " + result);
            }
            long memoryHandle = pBufferMemory.get(0);

            // 4. Liaison de la mémoire au buffer
            vkBindBufferMemory(context.getDevice(), bufferHandle, memoryHandle, 0);

            return new VulkanBuffer(bufferHandle, memoryHandle, size);
        }
    }
}
