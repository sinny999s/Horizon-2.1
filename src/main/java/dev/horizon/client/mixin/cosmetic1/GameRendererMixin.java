package dev.horizon.client.mixin.cosmetic1;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.PostRenderHandEvent;
import dev.horizon.client.event.RenderHandEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The first-person hand can be skipped, and is followed by an event. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void horizon$renderHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depth, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderHandEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
		shift = At.Shift.AFTER))
	private void horizon$postRenderHand(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
	                                    boolean consistentDepthRequired, CallbackInfo ci) {
		EventBus.INSTANCE.post(new PostRenderHandEvent());
	}
}
