package dev.horizon.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.horizon.client.render.World3D;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Render3D point: the whole level is drawn (clouds, weather and post chains included), the hand is not yet. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void horizon$render3d(CallbackInfo ci, @Local(name = "projectionMatrix") Matrix4f projectionMatrix,
	                              @Local(name = "cameraState") CameraRenderState cameraState) {
		World3D.INSTANCE.fire(cameraState, projectionMatrix);
	}
}
