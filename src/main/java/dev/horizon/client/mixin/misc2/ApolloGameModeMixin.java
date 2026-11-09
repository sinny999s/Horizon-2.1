package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apollo packet enrichment's attack and item-use messages, where Lunar Client 26.x hooks them (lunar/ApolloEnrichment). */
@Mixin(MultiPlayerGameMode.class)
public abstract class ApolloGameModeMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void horizon$apolloAttack(Player player, Entity target, CallbackInfo ci) {
		ApolloEnrichment.attack(target);
	}

	@Inject(method = "useItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V", shift = At.Shift.AFTER))
	private void horizon$apolloUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		ApolloEnrichment.useItem(hand);
	}
}
