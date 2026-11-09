package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Apollo server rules' brightness: the gamma Lunar Client uses instead of the video setting's (lunar/ApolloEnrichment). */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class ApolloRulesMixin {
	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0))
	private float horizon$apolloGamma(float gamma) {
		Float override = ApolloEnrichment.gamma();
		return override != null ? override : gamma;
	}
}
