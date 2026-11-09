package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloFeatures;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players an Apollo server turned upside down (players go through AvatarRenderer's own check). */
@Mixin(AvatarRenderer.class)
public abstract class ApolloAvatarUpsideDownMixin {
	@Inject(method = "isEntityUpsideDown(Lnet/minecraft/world/entity/Avatar;)Z", at = @At("RETURN"), cancellable = true)
	private void horizon$apolloFlip(Avatar avatar, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && ApolloFeatures.isFlipped(avatar.getId())) cir.setReturnValue(true);
	}
}
