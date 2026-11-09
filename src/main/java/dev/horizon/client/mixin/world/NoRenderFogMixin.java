package dev.horizon.client.mixin.world;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.GetFogBufferEvent;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A cancelled GetFogBufferEvent hands out the empty (no-fog) buffer.
 * Also pushes {@link FogData} distances to {@link Float#MAX_VALUE} at priority 900 before Sodium captures them at RETURN.
 */
@Mixin(value = FogRenderer.class, priority = 900)
public abstract class NoRenderFogMixin {
	@ModifyExpressionValue(method = "getBuffer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;fogEnabled:Z"))
	private boolean horizon$fog(boolean enabled) {
		return EventBus.INSTANCE.post(new GetFogBufferEvent()).getCancelled() ? false : enabled;
	}

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void horizon$setupFog(CallbackInfoReturnable<FogData> cir) {
		if (EventBus.INSTANCE.post(new GetFogBufferEvent()).getCancelled()) {
			FogData data = cir.getReturnValue();
			if (data != null) {
				data.environmentalStart = Float.MAX_VALUE;
				data.environmentalEnd = Float.MAX_VALUE;
				data.renderDistanceStart = Float.MAX_VALUE;
				data.renderDistanceEnd = Float.MAX_VALUE;
				data.skyEnd = Float.MAX_VALUE;
				data.cloudEnd = Float.MAX_VALUE;
			}
		}
	}
}
