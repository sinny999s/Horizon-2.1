package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.AttackEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cancelled AttackEvent makes `startAttack` return false. */
@Mixin(Minecraft.class)
public abstract class StartAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void horizon$attack(CallbackInfoReturnable<Boolean> cir) {
		if (EventBus.INSTANCE.post(new AttackEvent()).getCancelled()) cir.setReturnValue(false);
	}
}
