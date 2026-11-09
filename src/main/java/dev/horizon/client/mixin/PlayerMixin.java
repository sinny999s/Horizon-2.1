package dev.horizon.client.mixin;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.TravelEvent;
import dev.horizon.client.module.legit.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Around the local player's travel (movement physics; cancelling the pre event skips travel and the post event), and
 * Reach's interaction ranges (any player, on purpose).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void horizon$travel(Vec3 input, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && EventBus.INSTANCE.post(new TravelEvent(true)).getCancelled()) ci.cancel();
	}

	@Inject(method = "travel", at = @At("RETURN"))
	private void horizon$postTravel(Vec3 input, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player) EventBus.INSTANCE.post(new TravelEvent(false));
	}

	@Inject(method = "blockInteractionRange", at = @At("HEAD"), cancellable = true)
	private void horizon$blockRange(CallbackInfoReturnable<Double> cir) {
		Double r = Reach.blockRange();
		if (r != null) cir.setReturnValue(r);
	}

	@Inject(method = "entityInteractionRange", at = @At("HEAD"), cancellable = true)
	private void horizon$entityRange(CallbackInfoReturnable<Double> cir) {
		Double r = Reach.entityRange();
		if (r != null) cir.setReturnValue(r);
	}
}
