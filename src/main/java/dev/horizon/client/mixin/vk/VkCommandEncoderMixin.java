package dev.horizon.client.mixin.vk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory;
import dev.horizon.client.render.vk.Barriers;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * GPU Sync ({@link Barriers}): Minecraft's full barrier after every render pass, clear, upload and copy is skipped
 * while it is active, each of those commands reports what it touches first, and a render pass begins at its first
 * draw. Ends of the command buffer and other command buffers added to the submit stay fully synchronized.
 * <p>
 * Fence polls: at the end of every frame each of Minecraft's vertex buffer pools asks whether the buffers it used a
 * frame ago are free again, a driver call each (vkWaitSemaphores with no timeout), dozens a frame. The GPU's progress
 * (the timeline semaphore's value) is read once and reused for 0.1 ms, so that burst costs one call: a buffer that
 * frees up in between is reused a little later, nothing else changes. A loop polling until the GPU is done still sees
 * it finish (a fresh read every 0.1 ms). Waits with a timeout still wait.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VkCommandEncoderMixin {
	@Shadow private VkCommandBuffer currentCommandBuffer;
	@Shadow @Final private VulkanTransientMemory transientMemory;
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private long submitSemaphore;
	@Shadow private long currentSubmitIndex;
	@Shadow private long completedSubmitIndex;
	/** When the GPU's progress was last read ({@link #horizon$pollOnce}), System.nanoTime. */
	@Unique private long horizon$polledAt;
	@Unique private boolean horizon$polled;

	@Inject(method = "awaitSubmitCompletion", at = @At("HEAD"), cancellable = true)
	private void horizon$pollOnce(long submitIndex, long timeoutNS, CallbackInfoReturnable<Boolean> cir) {
		// Minecraft answers these itself without the driver: already known done, or this frame's own submit.
		if (!Barriers.cachedPolls || timeoutNS != 0L || this.completedSubmitIndex >= submitIndex || submitIndex >= this.currentSubmitIndex) return;
		long now = System.nanoTime();
		if (this.horizon$polled && now - this.horizon$polledAt < 100_000L) {
			// Read just now, and it wasn't done then.
			cir.setReturnValue(false);
			return;
		}
		this.horizon$polled = true;
		this.horizon$polledAt = now;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer value = stack.mallocLong(1);
			if (VK12.vkGetSemaphoreCounterValue(this.device.vkDevice(), this.submitSemaphore, value) != 0) return;
			if (value.get(0) > this.completedSubmitIndex) this.completedSubmitIndex = value.get(0);
			cir.setReturnValue(value.get(0) >= submitIndex);
		}
	}

	@Shadow private VkCommandBuffer commandBuffer() { throw new AssertionError(); }

	@Shadow private void memoryBarrier(MemoryStack stack) { throw new AssertionError(); }

	@Redirect(method = {"submitRenderPass", "clearColorTexture", "clearDepthTexture", "writeToBuffer", "copyToBuffer", "writeToTexture",
		"copyBufferToTexture", "copyTextureToTexture",
		"clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
		"copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V"},
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"),
		require = 10)
	private void horizon$barrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (!Barriers.active) this.memoryBarrier(stack);
	}

	@Inject(method = "clearColorTexture", at = @At("HEAD"))
	private void horizon$clearColor(GpuTexture texture, Vector4fc color, CallbackInfo ci) {
		if (Barriers.active) Barriers.writeImage(this.commandBuffer(), texture, null);
	}

	@Inject(method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
		at = @At("HEAD"))
	private void horizon$clearBoth(GpuTexture color, Vector4fc value, GpuTexture depth, double clearDepth, CallbackInfo ci) {
		if (Barriers.active) Barriers.writeImage(this.commandBuffer(), color, depth);
	}

	@Inject(method = "clearDepthTexture", at = @At("HEAD"))
	private void horizon$clearDepth(GpuTexture texture, double value, CallbackInfo ci) {
		if (Barriers.active) Barriers.writeImage(this.commandBuffer(), texture, null);
	}

	@Inject(method = "writeToBuffer", at = @At("HEAD"))
	private void horizon$writeBuffer(GpuBufferSlice destination, ByteBuffer data, CallbackInfo ci) {
		if (Barriers.active) Barriers.writeBuffer(this.commandBuffer(), destination);
	}

	@Inject(method = "copyToBuffer", at = @At("HEAD"))
	private void horizon$copyBuffer(GpuBufferSlice source, GpuBufferSlice target, CallbackInfo ci) {
		if (Barriers.active) Barriers.copyBuffer(this.commandBuffer(), source, target);
	}

	@Inject(method = "writeToTexture", at = @At("HEAD"))
	private void horizon$writeTexture(GpuTexture texture, ByteBuffer data, int a, int b, int c, int d, int e, int f, CallbackInfo ci) {
		if (Barriers.active) Barriers.writeImage(this.commandBuffer(), texture, null);
	}

	@Inject(method = "copyBufferToTexture", at = @At("HEAD"))
	private void horizon$copyBufferToTexture(GpuBufferSlice source, int a, int b, int c, int d, GpuTexture target, int e, int f, int g, int h,
	                                         int i, int j, CallbackInfo ci) {
		if (Barriers.active) Barriers.copyBufferToImage(this.commandBuffer(), source, target);
	}

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At("HEAD"))
	private void horizon$copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip, int x, int y,
	                                         int width, int height, CallbackInfo ci) {
		if (Barriers.active) Barriers.copyImageToBuffer(this.commandBuffer(), source, destination, offset);
	}

	@Inject(method = "copyTextureToTexture", at = @At("HEAD"))
	private void horizon$copyTexture(GpuTexture source, GpuTexture destination, int a, int b, int c, int d, int e, int f, int g, CallbackInfo ci) {
		if (Barriers.active) Barriers.copyImage(this.commandBuffer(), source, destination);
	}

	@Inject(method = "createRenderPass", at = @At("HEAD"))
	private void horizon$passCreate(RenderPassDescriptor descriptor, CallbackInfoReturnable<?> cir) {
		Barriers.passCreate(descriptor);
	}

	@Redirect(method = "createRenderPass", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRDynamicRendering;vkCmdBeginRenderingKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkRenderingInfo;)V"))
	private void horizon$beginRendering(VkCommandBuffer cb, VkRenderingInfo info) {
		Barriers.beginRendering(cb, info);
	}

	@Redirect(method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V",
		at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdClearAttachments(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkClearAttachment$Buffer;Lorg/lwjgl/vulkan/VkClearRect$Buffer;)V"))
	private void horizon$clearAttachments(VkCommandBuffer cb, VkClearAttachment.Buffer attachments, VkClearRect.Buffer rects) {
		Barriers.ensureBegun(cb);
		VK12.vkCmdClearAttachments(cb, attachments, rects);
	}

	@Inject(method = "submitRenderPass", at = @At("HEAD"))
	private void horizon$passEnd(CallbackInfo ci) {
		Barriers.passEnd(this.currentCommandBuffer);
	}

	@Inject(method = "endCommandBuffer", at = @At("HEAD"))
	private void horizon$endCommandBuffer(CallbackInfo ci) {
		Barriers.endCommandBuffer(this.currentCommandBuffer);
	}

	@Inject(method = "execute", at = @At("TAIL"))
	private void horizon$execute(VkCommandBuffer cb, CallbackInfo ci) {
		if (cb != ((VkTransientMemoryAccessor) this.transientMemory).horizon$commandBuffer()) Barriers.external();
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void horizon$submit(CallbackInfo ci) {
		Barriers.frameEnd();
	}
}
