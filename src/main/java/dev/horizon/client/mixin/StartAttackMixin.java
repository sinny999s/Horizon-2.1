package dev.horizon.client.mixin;

import dev.horizon.client.combat.Swings;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks vanilla's own left click, which sends its swing itself (Swings). */
@Mixin(Minecraft.class)
public abstract class StartAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void horizon$start(CallbackInfoReturnable<Boolean> cir) {
		Swings.inVanillaAttack = true;
	}

	@Inject(method = "startAttack", at = @At("RETURN"))
	private void horizon$end(CallbackInfoReturnable<Boolean> cir) {
		Swings.inVanillaAttack = false;
	}
}
