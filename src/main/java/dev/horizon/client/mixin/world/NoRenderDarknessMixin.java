package dev.horizon.client.mixin.world;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.horizon.client.event.DarknessEffectEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The darkness effect's light pulse and brightness drop. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class NoRenderDarknessMixin {
	@Inject(method = "calculateDarknessScale", at = @At("HEAD"), cancellable = true)
	private void horizon$darkness(LivingEntity entity, float darknessGamma, float partialTick, CallbackInfoReturnable<Float> cir) {
		if (EventBus.INSTANCE.post(new DarknessEffectEvent()).getCancelled()) cir.setReturnValue(0.0F);
	}

	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
	private float horizon$darknessBlend(float factor) {
		return EventBus.INSTANCE.post(new DarknessEffectEvent()).getCancelled() ? 0.0F : factor;
	}
}
