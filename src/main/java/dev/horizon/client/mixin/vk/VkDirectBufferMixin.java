package dev.horizon.client.mixin.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.horizon.client.render.vk.Barriers;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.Vma;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Buffers stay mapped: Minecraft maps a host-visible buffer through the allocator for every write and unmaps it after
 * (uniform rings, vertex uploads, fog), two allocator calls each time. The first map is kept (and its address reused by
 * later maps, whose unmaps then do nothing) until the buffer is destroyed, where it's unmapped once. Same memory, same
 * data; {@link VkDirectBufferViewMixin} is the unmap side.
 */
@Mixin(VulkanGpuBuffer.Direct.class)
public abstract class VkDirectBufferMixin {
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private long vmaAllocation;
	@Unique private long horizon$address;

	@Redirect(method = "map", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaMapMemory(JJLorg/lwjgl/PointerBuffer;)I"))
	private int horizon$map(long vma, long allocation, PointerBuffer out) {
		if (this.horizon$address != 0L) {
			if (Barriers.keepMapped) {
				out.put(0, this.horizon$address);
				return 0;
			}
			// Switched off (tests): give the kept map back; no view of it is open between Minecraft's map calls.
			this.horizon$address = 0L;
			Barriers.keptMapped(allocation, false);
			Vma.vmaUnmapMemory(vma, allocation);
		}
		int result = Vma.vmaMapMemory(vma, allocation, out);
		if (result == 0 && Barriers.keepMapped) {
			this.horizon$address = out.get(0);
			Barriers.keptMapped(allocation, true);
		}
		return result;
	}

	@Inject(method = "destroy", at = @At("HEAD"))
	private void horizon$unmapKept(CallbackInfo ci) {
		if (this.horizon$address != 0L) {
			this.horizon$address = 0L;
			Barriers.keptMapped(this.vmaAllocation, false);
			Vma.vmaUnmapMemory(this.device.vma(), this.vmaAllocation);
		}
	}
}
