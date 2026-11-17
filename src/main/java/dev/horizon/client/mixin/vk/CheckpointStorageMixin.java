package dev.horizon.client.mixin.vk;

import dev.horizon.client.config.Storage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Options > Horizon > Performance > GPU Crash Markers (off by default): Minecraft writes a GPU marker for every pipeline
 * stage at the start and end of every render pass (and builds the pass's label string for it), only read if the GPU
 * hangs, to name the pass it hung in. Off, none are written; a hang report just lacks that line.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.checkpoints.AbstractCheckpointStorage")
public abstract class CheckpointStorageMixin {
	@Inject(method = "recordCheckpoint(Lorg/lwjgl/vulkan/VkCommandBuffer;Lcom/mojang/renderpearl/backend/vulkan/checkpoints/CheckpointExtension$CheckpointType;Ljava/util/function/Supplier;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$skip(VkCommandBuffer commandBuffer, @Coerce Object type, Supplier<String> label, CallbackInfo ci) {
		if (!Storage.INSTANCE.getShared().getCrashMarkers()) ci.cancel();
	}
}
