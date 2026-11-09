package dev.horizon.client.mixin.cosmetic2;

import dev.horizon.client.event.CameraOffsetEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The third-person camera distance from CameraOffsetEvent, unclipped. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract float getMaxZoom(float cameraDist);

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V", ordinal = 0), index = 0)
	private float horizon$distance(float x) {
		CameraOffsetEvent e = EventBus.INSTANCE.post(new CameraOffsetEvent());
		return e.getCancelled() ? -getMaxZoom(e.getOffset()) : x;
	}

	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void horizon$noClip(float cameraDist, CallbackInfoReturnable<Float> cir) {
		CameraOffsetEvent e = EventBus.INSTANCE.post(new CameraOffsetEvent());
		if (e.getCancelled()) cir.setReturnValue(e.getOffset());
	}
}
