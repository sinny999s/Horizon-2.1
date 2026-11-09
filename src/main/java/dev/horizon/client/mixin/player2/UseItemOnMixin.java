package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.InteractBlockEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cancelled block click returns PASS (vanilla then uses the item). */
@Mixin(MultiPlayerGameMode.class)
public abstract class UseItemOnMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void horizon$interactBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (EventBus.INSTANCE.post(new InteractBlockEvent(hand, hit)).getCancelled()) cir.setReturnValue(InteractionResult.PASS);
	}
}
