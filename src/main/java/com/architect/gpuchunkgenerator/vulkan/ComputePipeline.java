package com.architect.gpuchunkgenerator.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Encapsule le pipeline de calcul Vulkan.
 */
public class ComputePipeline {

    private final VulkanContext context;
    
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long shaderModule;
    private long pipeline;

    public ComputePipeline(VulkanContext context, ByteBuffer spirvCode) {
        this.context = context;
        createPipeline(spirvCode);
    }

    private void createPipeline(ByteBuffer spirvCode) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.getDevice();

            // 1. Descriptor Set Layout (5 Bindings)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(5, stack);
            
            // Binding 0: Output SSBO
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: Legacy Noise SSBO (pour blendedNoise)
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: Spline LUT SSBO (Legacy - à migrer/réutiliser pour Multi-LUT)
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 3: Noise Parameters SSBO (Instance-specific)
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 4: Multi-LUT Splines Parameters SSBO
            bindings.get(4)
                    .binding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) != VK_SUCCESS) {
                throw new RuntimeException("Échec de la création du Descriptor Set Layout.");
            }
            descriptorSetLayout = pLayout.get(0);

            // 2. Pipeline Layout avec Push Constants
            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(20); // 5 ints (chunkX, chunkZ, minY, height, maxIndex)

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushConstants);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Échec de la création du Pipeline Layout.");
            }
            pipelineLayout = pPipelineLayout.get(0);

            // 3. Shader Module
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);
            if (vkCreateShaderModule(device, moduleInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Échec de la création du Shader Module.");
            }
            shaderModule = pShaderModule.get(0);

            // 4. Compute Pipeline
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .layout(pipelineLayout)
                    .stage(VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shaderModule)
                            .pName(stack.UTF8("main")));

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Échec de la création du Compute Pipeline.");
            }
            pipeline = pPipeline.get(0);
        }
    }

    public long getDescriptorSetLayout() { return descriptorSetLayout; }
    public long getPipelineLayout() { return pipelineLayout; }
    public long getPipeline() { return pipeline; }

    public void destroy() {
        VkDevice device = context.getDevice();
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyShaderModule(device, shaderModule, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    }
}
