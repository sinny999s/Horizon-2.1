package dev.horizon.client.mixin.world;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.WeatherRenderEvent;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rain/snow and the world border. 26.2 drew them in `addWeatherPass`; 26.3 has no such
 * pass: both are drawn inside the main pass, by the classic or the order-independent-transparency path, so each of those
 * draws is cancelled here and in {@link NoRenderWorldBorderMixin}.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class NoRenderWeatherMixin {
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
		at = @At("HEAD"), cancellable = true)
	private void horizon$weather(WeatherRenderState state, RenderPass pass, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new WeatherRenderEvent()).getCancelled()) ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void horizon$weatherOit(OitStage stage, WeatherRenderState state, RenderPass pass, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new WeatherRenderEvent()).getCancelled()) ci.cancel();
	}
}
