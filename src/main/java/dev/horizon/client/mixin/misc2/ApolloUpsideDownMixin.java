package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Not in the original: entities an Apollo server turned upside down (Apollo's entity module, FlipEntityMessage). */
@Mixin(LivingEntityRenderer.class)
public abstract class ApolloUpsideDownMixin {
	@Inject(method = "isEntityUpsideDown", at = @At("RETURN"), cancellable = true)
	private void horizon$apolloFlip(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && ApolloFeatures.isFlipped(entity.getId())) cir.setReturnValue(true);
	}
}
