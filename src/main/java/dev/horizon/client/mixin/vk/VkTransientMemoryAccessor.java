package dev.horizon.client.mixin.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft's own upload command buffer, which GPU Sync doesn't treat as unknown work (it ends with a full barrier). */
@Mixin(VulkanTransientMemory.class)
public interface VkTransientMemoryAccessor {
	@Accessor("commandBuffer")
	VkCommandBuffer horizon$commandBuffer();
}
