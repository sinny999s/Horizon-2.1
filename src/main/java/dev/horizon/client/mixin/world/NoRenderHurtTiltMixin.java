package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.HurtTiltEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The camera tilt when hurt. */
@Mixin(GameRenderer.class)
public abstract class NoRenderHurtTiltMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void horizon$hurtTilt(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new HurtTiltEvent()).getCancelled()) ci.cancel();
	}
}
