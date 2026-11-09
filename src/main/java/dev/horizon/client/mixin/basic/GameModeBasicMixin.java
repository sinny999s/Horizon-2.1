package dev.horizon.client.mixin.basic;

import dev.horizon.client.combat.Rubberband;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Letting go of a used item (only for the chorus timestamp). */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeBasicMixin {
	@Inject(method = "releaseUsingItem", at = @At("HEAD"))
	private void horizon$releaseUsingItem(Player player, CallbackInfo ci) {
		Rubberband.INSTANCE.stopUsing();
	}
}
