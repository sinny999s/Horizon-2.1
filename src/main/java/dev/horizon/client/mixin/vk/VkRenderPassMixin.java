package dev.horizon.client.mixin.vk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.horizon.client.render.vk.Barriers;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GPU Sync ({@link Barriers}): a render pass reports its bindings and begins (or splits) before a draw that needs it. */
@Mixin(VulkanRenderPass.class)
public abstract class VkRenderPassMixin {
	@Shadow @Final private VkCommandBuffer commandBuffer;
	@Shadow @Final protected ReferenceList<Object> uniforms;

	@Inject(method = "setUniform", at = @At("HEAD"))
	private void horizon$uniform(int index, Object value, CallbackInfo ci) {
		if (Barriers.active) Barriers.uniformsChanged();
	}

	@Inject(method = "setVertexBuffer", at = @At("HEAD"))
	private void horizon$vertex(int slot, GpuBufferSlice slice, CallbackInfo ci) {
		if (Barriers.active) Barriers.vertexBuffer(slot, slice);
	}

	@Inject(method = "setIndexBuffer", at = @At("HEAD"))
	private void horizon$index(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
		if (Barriers.active) Barriers.indexBuffer(buffer);
	}

	@Inject(method = {"drawIndexedIndirect", "drawIndirect"}, at = @At("HEAD"))
	private void horizon$indirect(GpuBufferSlice commands, int count, CallbackInfo ci) {
		if (Barriers.active) Barriers.indirect(commands);
	}

	@Inject(method = "pushDescriptors", at = @At("HEAD"))
	private void horizon$draw(CallbackInfo ci) {
		if (Barriers.active) Barriers.beforeDraw(this.commandBuffer, this.uniforms);
	}
}
