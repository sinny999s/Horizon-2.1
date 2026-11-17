package dev.horizon.client.mixin.vk;

import dev.horizon.client.render.vk.Barriers;
import org.lwjgl.util.vma.Vma;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The unmap of a mapped view of a buffer that stays mapped ({@link VkDirectBufferMixin}) does nothing. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer$Direct$1")
public abstract class VkDirectBufferViewMixin {
	@Redirect(method = "run", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaUnmapMemory(JJ)V"))
	private void horizon$unmap(long vma, long allocation) {
		if (!Barriers.isKeptMapped(allocation)) Vma.vmaUnmapMemory(vma, allocation);
	}
}
