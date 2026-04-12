package com.architect.gpuchunkgenerator.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Orchestrateur Vulkan pour l'exécution des calculs GPU.
 */
public class CommandManager {

    private final VulkanContext context;
    private final MemoryAllocator allocator;
    private ComputePipeline pipeline;

    private VulkanBuffer noisePermutationBuffer; // Binding 1: Legacy Noise
    private VulkanBuffer multiSplineLutBuffer;   // Binding 4: Multi-LUT Splines
    private VulkanBuffer noiseParamsBuffer;      // Binding 3: Instance-specific Noise Parameters
    private VulkanBuffer legacySplineLutBuffer;  // Binding 2: (Désuet)
    
    // Ressources persistantes
    private long commandPool;
    private VkCommandBuffer commandBuffer;
    private long fence;
    
    private long descriptorPool;
    private long descriptorSet;
    
    private final VulkanBuffer deviceBuffer;
    private final VulkanBuffer stagingBuffer;
    private final ByteBuffer mappedStagingBuffer;
    
    public static final int MAX_HEIGHT = 4096;
    public static final int MAX_INDEX = 16 * MAX_HEIGHT * 16;
    public static final long BUFFER_SIZE = MAX_INDEX * 4L;

    public CommandManager(VulkanContext context, ComputePipeline pipeline, MemoryAllocator allocator) {
        this.context = context;
        this.pipeline = pipeline;
        this.allocator = allocator;

        // 1. Allocation des buffers (VRAM et RAM)
        this.deviceBuffer = allocator.createBuffer(BUFFER_SIZE, 
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        
        this.stagingBuffer = allocator.createBuffer(BUFFER_SIZE, 
                VK_BUFFER_USAGE_TRANSFER_DST_BIT, 
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        // 2. Mapping persistant
        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        vkMapMemory(context.getDevice(), stagingBuffer.memoryHandle(), 0, BUFFER_SIZE, 0, pData);
        this.mappedStagingBuffer = MemoryUtil.memByteBuffer(pData.get(0), (int) BUFFER_SIZE).order(ByteOrder.nativeOrder());
        MemoryUtil.memFree(pData);

        // 3. Pool de commandes et Buffer
        initCommands();
        
        // 4. Descriptor Set
        initDescriptors();
        
        // 5. Fence pour la synchronisation
        initSync();
        
        System.out.println("[Vulkan] CommandManager initialisé avec succès.");
    }

    private void initCommands() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(context.getComputeQueueFamilyIndex())
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(context.getDevice(), poolInfo, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec de création du Command Pool : " + result);
            }
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(context.getDevice(), allocInfo, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Échec d'allocation du Command Buffer.");
            }
            commandBuffer = new VkCommandBuffer(pBuffer.get(0), context.getDevice());
        }
    }

    private void initDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Pool (5 descripteurs : Output + LegacyNoise + LegacySpline + NoiseParams + MultiSpline)
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(4).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(context.getDevice(), poolInfo, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec de création du Descriptor Pool : " + result);
            }
            descriptorPool = pPool.get(0);

            // Allocation Set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(pipeline.getDescriptorSetLayout()));

            LongBuffer pSet = stack.mallocLong(1);
            result = vkAllocateDescriptorSets(context.getDevice(), allocInfo, pSet);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec d'allocation du Descriptor Set : " + result);
            }
            descriptorSet = pSet.get(0);

            // Liaison initiale (Output uniquement)
            updateDescriptors();
        }
    }

    private void updateDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int writeCount = 1;
            if (noisePermutationBuffer != null) writeCount++;
            if (legacySplineLutBuffer != null) writeCount++;
            if (noiseParamsBuffer != null) writeCount++;
            if (multiSplineLutBuffer != null) writeCount++;
            
            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(writeCount, stack);
            int currentWrite = 0;

            // Binding 0 : Output
            VkDescriptorBufferInfo.Buffer outputInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(deviceBuffer.bufferHandle())
                    .offset(0)
                    .range(BUFFER_SIZE);

            descriptorWrites.get(currentWrite++)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(outputInfo);

            // Binding 1 : Noise (si présent)
            if (noisePermutationBuffer != null) {
                VkDescriptorBufferInfo.Buffer noiseInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(noisePermutationBuffer.bufferHandle())
                        .offset(0)
                        .range(noisePermutationBuffer.size());

                descriptorWrites.get(currentWrite++)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(1)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(noiseInfo);
            }

            // Binding 2 : Legacy Spline LUT (si présent)
            if (legacySplineLutBuffer != null) {
                VkDescriptorBufferInfo.Buffer lutInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(legacySplineLutBuffer.bufferHandle())
                        .offset(0)
                        .range(legacySplineLutBuffer.size());

                descriptorWrites.get(currentWrite++)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(2)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(lutInfo);
            }

            // Binding 3 : Noise Parameters (si présent)
            if (noiseParamsBuffer != null) {
                VkDescriptorBufferInfo.Buffer paramsInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(noiseParamsBuffer.bufferHandle())
                        .offset(0)
                        .range(noiseParamsBuffer.size());

                descriptorWrites.get(currentWrite++)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(3)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(paramsInfo);
            }

            // Binding 4 : Multi-LUT Splines
            if (multiSplineLutBuffer != null) {
                VkDescriptorBufferInfo.Buffer multiLutInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(multiSplineLutBuffer.bufferHandle())
                        .offset(0)
                        .range(multiSplineLutBuffer.size());

                descriptorWrites.get(currentWrite++)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(4)
                        .descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(multiLutInfo);
            }

            vkUpdateDescriptorSets(context.getDevice(), descriptorWrites, null);
        }
    }

    /**
     * Uploade les nouveaux paramètres de bruit détaillés (DoublePerlin) au GPU.
     */
    public void uploadNoiseParameters(java.util.List<com.architect.gpuchunkgenerator.ast.ir.GpuNode.Noise> noises) {
        if (noises == null || noises.isEmpty()) return;

        System.out.println("[Vulkan] Injection de " + noises.size() + " instances de bruit DoublePerlin en VRAM...");

        if (noiseParamsBuffer != null) {
            noiseParamsBuffer.destroy(context.getDevice());
        }

        // 1. Calcul de la taille totale du buffer
        long totalSize = 0;
        for (com.architect.gpuchunkgenerator.ast.ir.GpuNode.Noise noise : noises) {
            int n1 = noise.firstSampler().amplitudes().length;
            int n2 = noise.secondSampler().amplitudes().length;
            totalSize += 20; // Header (GlobalAmp, F1, N1, F2, N2)
            totalSize += (long) (n1 + n2) * 4;   // Amplitudes
            totalSize += (long) (n1 + n2) * 512 * 4; // Permutations (4 octets par int / p[i])
        }

        // 2. Allocation
        noiseParamsBuffer = allocator.createBuffer(totalSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        // 3. Sérialisation
        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        vkMapMemory(context.getDevice(), noiseParamsBuffer.memoryHandle(), 0, totalSize, 0, pData);
        ByteBuffer buffer = MemoryUtil.memByteBuffer(pData.get(0), (int) totalSize).order(ByteOrder.nativeOrder());

        for (com.architect.gpuchunkgenerator.ast.ir.GpuNode.Noise noise : noises) {
            // Header (20 octets)
            buffer.putFloat(noise.globalAmplitude());
            buffer.putInt(noise.firstSampler().firstOctave());  // F1
            buffer.putInt(noise.firstSampler().amplitudes().length); // N1
            buffer.putInt(noise.secondSampler().firstOctave()); // F2
            buffer.putInt(noise.secondSampler().amplitudes().length); // N2

            // Amplitudes S1
            for (float a : noise.firstSampler().amplitudes()) buffer.putFloat(a);
            // Amplitudes S2
            for (float a : noise.secondSampler().amplitudes()) buffer.putFloat(a);

            // Permutations S1
            for (byte[] p : noise.firstSampler().permutations()) {
                for (int i = 0; i < 512; i++) buffer.putInt(p[i] & 0xFF);
            }
            // Permutations S2
            for (byte[] p : noise.secondSampler().permutations()) {
                for (int i = 0; i < 512; i++) buffer.putInt(p[i] & 0xFF);
            }
        }

        vkUnmapMemory(context.getDevice(), noiseParamsBuffer.memoryHandle());
        MemoryUtil.memFree(pData);

        updateDescriptors();
        System.out.println("[Vulkan] Paramètres de bruit injectés (" + totalSize + " octets).");
    }

    public void uploadNoisePermutations(java.util.List<byte[]> octaves) {
        if (octaves == null || octaves.isEmpty()) {
            return;
        }

        System.out.println("[Vulkan] Injection de " + octaves.size() + " octaves de bruit 'Legacy' en VRAM...");

        if (noisePermutationBuffer != null) {
            noisePermutationBuffer.destroy(context.getDevice());
        }

        // Chaque octave fait 512 entiers (pour coller au layout std430 int[])
        long totalSize = (long) octaves.size() * 512 * 4;
        noisePermutationBuffer = allocator.createBuffer(totalSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        vkMapMemory(context.getDevice(), noisePermutationBuffer.memoryHandle(), 0, totalSize, 0, pData);
        java.nio.IntBuffer intBuffer = MemoryUtil.memIntBuffer(pData.get(0), (int) (totalSize / 4));

        for (byte[] p : octaves) {
            for (int i = 0; i < 512; i++) {
                intBuffer.put(p[i % p.length] & 0xFF);
            }
        }

        vkUnmapMemory(context.getDevice(), noisePermutationBuffer.memoryHandle());
        MemoryUtil.memFree(pData);

        updateDescriptors();
        System.out.println("[Vulkan] Permutations 'Legacy' injectées.");
    }

    public void uploadMultiSplineLuts(java.util.List<com.architect.gpuchunkgenerator.ast.SplineRegistry.BakedSplineData> splines) {
        if (splines == null || splines.isEmpty()) return;

        System.out.println("[Vulkan] Injection de " + splines.size() + " LUTs de splines en VRAM...");

        if (multiSplineLutBuffer != null) {
            multiSplineLutBuffer.destroy(context.getDevice());
        }

        long totalSize = (long) splines.size() * com.architect.gpuchunkgenerator.ast.SplineRegistry.RESOLUTION * 4L;
        multiSplineLutBuffer = allocator.createBuffer(totalSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        vkMapMemory(context.getDevice(), multiSplineLutBuffer.memoryHandle(), 0, totalSize, 0, pData);
        java.nio.FloatBuffer floatBuffer = MemoryUtil.memFloatBuffer(pData.get(0), (int) (totalSize / 4));

        for (com.architect.gpuchunkgenerator.ast.SplineRegistry.BakedSplineData data : splines) {
            floatBuffer.put(data.lut());
        }
        
        vkUnmapMemory(context.getDevice(), multiSplineLutBuffer.memoryHandle());
        MemoryUtil.memFree(pData);

        updateDescriptors();
        System.out.println("[Vulkan] Multi-LUT Splines injectées.");
    }

    public void uploadSplineLut(float[] lut) {
        if (lut == null || lut.length == 0) return;

        System.out.println("[Vulkan] Injection de la LUT de Spline (Legacy) en VRAM...");

        if (legacySplineLutBuffer != null) {
            legacySplineLutBuffer.destroy(context.getDevice());
        }

        long totalSize = (long) lut.length * 4L;
        legacySplineLutBuffer = allocator.createBuffer(totalSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        PointerBuffer pData = MemoryUtil.memAllocPointer(1);
        vkMapMemory(context.getDevice(), legacySplineLutBuffer.memoryHandle(), 0, totalSize, 0, pData);
        java.nio.FloatBuffer floatBuffer = MemoryUtil.memFloatBuffer(pData.get(0), lut.length);
        floatBuffer.put(lut);
        
        vkUnmapMemory(context.getDevice(), legacySplineLutBuffer.memoryHandle());
        MemoryUtil.memFree(pData);

        updateDescriptors();
    }

    private void initSync() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            int result = vkCreateFence(context.getDevice(), fenceInfo, null, pFence);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Échec de création de la Fence : " + result);
            }
            fence = pFence.get(0);
        }
    }

    /**
     * Génère un chunk sur le GPU et récupère les densités brutes (Thread-Safe).
     */
    public synchronized float[] generateChunkDensities(int chunkX, int chunkZ, int minY, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Enregistrement des commandes
            vkResetCommandBuffer(commandBuffer, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            vkBeginCommandBuffer(commandBuffer, beginInfo);

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipeline());
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipelineLayout(), 0, stack.longs(descriptorSet), null);

            // Push Constants: chunkX, chunkZ, minY, height, maxIndex
            IntBuffer pushData = stack.ints(chunkX, chunkZ, minY, height, MAX_INDEX);
            vkCmdPushConstants(commandBuffer, pipeline.getPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);

            // Dispatch: 1 group de 16x16 threads couvre tout le plan XZ du chunk.
            vkCmdDispatch(commandBuffer, 1, 1, 1);

            // Barrière Mémoire : Compute Write -> Transfer Read
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .buffer(deviceBuffer.bufferHandle())
                    .offset(0)
                    .size(BUFFER_SIZE);

            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, barrier, null);

            // Copie VRAM -> RAM (Staging)
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                    .size(BUFFER_SIZE);
            vkCmdCopyBuffer(commandBuffer, deviceBuffer.bufferHandle(), stagingBuffer.bufferHandle(), copyRegion);

            vkEndCommandBuffer(commandBuffer);

            // 2. Soumission
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));

            vkQueueSubmit(context.getComputeQueue(), submitInfo, fence);

            // 3. Attente et Reset
            vkWaitForFences(context.getDevice(), fence, true, Long.MAX_VALUE);
            vkResetFences(context.getDevice(), fence);

            // 4. Lecture des résultats (depuis le mapping persistant) via FloatBuffer
            float[] densities = new float[16 * height * 16];
            mappedStagingBuffer.position(0);
            mappedStagingBuffer.asFloatBuffer().get(densities);
            
            return densities;
        }
    }

    public void setPipeline(ComputePipeline pipeline) {
        this.pipeline = pipeline;
    }

    public ComputePipeline getComputePipeline() {
        return pipeline;
    }

    public void cleanup() {
        VkDevice device = context.getDevice();
        vkDestroyFence(device, fence, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyCommandPool(device, commandPool, null);
        
        // Destruction de la pipeline (Important pour éviter les fuites signalées par les Validation Layers)
        if (pipeline != null) {
            pipeline.destroy();
        }

        if (noiseParamsBuffer != null) {
            noiseParamsBuffer.destroy(device);
        }

        if (multiSplineLutBuffer != null) {
            multiSplineLutBuffer.destroy(device);
        }

        if (legacySplineLutBuffer != null) {
            legacySplineLutBuffer.destroy(device);
        }

        vkUnmapMemory(device, stagingBuffer.memoryHandle());
        deviceBuffer.destroy(device);
        stagingBuffer.destroy(device);
    }
}
