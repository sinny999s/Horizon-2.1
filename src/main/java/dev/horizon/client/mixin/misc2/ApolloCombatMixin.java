package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Apollo's combat module, as Lunar Client does it when the server allows (lunar/ApolloEnrichment): no miss penalty (the
 * swing cooldown cleared as an attack starts), and digging and using together (`allow-dig-and-use`: attacking isn't held
 * back by using an item, nor using by digging).
 */
@Mixin(Minecraft.class)
public abstract class ApolloCombatMixin {
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void horizon$apolloMissPenalty(CallbackInfoReturnable<Boolean> cir) {
		ApolloEnrichment.startAttack();
	}

	@ModifyExpressionValue(method = "continueAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
	private boolean horizon$apolloDigWhileUsing(boolean using) {
		return using && !ApolloEnrichment.digAndUse();
	}

	@ModifyExpressionValue(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;isDestroying()Z"))
	private boolean horizon$apolloUseWhileDigging(boolean digging) {
		return digging && !ApolloEnrichment.digAndUse();
	}
}
