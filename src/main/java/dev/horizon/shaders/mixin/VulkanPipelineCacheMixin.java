package dev.horizon.shaders.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.horizon.shaders.runtime.PipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanPipelineCacheMixin {
    @ModifyArg(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        ),
        index = 1
    )
    private static long sulkan$usePipelineCache(long cache, @Local(argsOnly = true) VulkanDevice device) {
        return cache != 0L ? cache : PipelineCache.handle(device);
    }
}
