package dev.horizon.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.horizon.client.module.cosmetic.MotionBlur;
import dev.horizon.client.render.World3D;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Render3D point: the whole level is drawn (clouds, weather and post chains included), the hand is not yet. Motion
 * Blur's two points: the hand's depth about to be cleared (the world's is still there), and the level finished.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void horizon$render3d(CallbackInfo ci, @Local(name = "projectionMatrix") Matrix4f projectionMatrix,
	                              @Local(name = "cameraState") CameraRenderState cameraState) {
		World3D.INSTANCE.fire(cameraState, projectionMatrix);
	}

	/** Before the hand: without a shader pack the world is finished here (and its depth not yet cleared). */
	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void horizon$beforeHand(CallbackInfo ci, @Local(name = "depthTextureView") GpuTextureView depthTextureView) {
		MotionBlur.beforeHand(depthTextureView);
	}

	/** After the level, the hand and a shader pack's composite (its own renderLevel RETURN hook); before outlines, post effects and the GUI. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void horizon$afterLevel(CallbackInfo ci) {
		MotionBlur.afterLevel();
	}
}
