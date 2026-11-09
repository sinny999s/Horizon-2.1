package dev.horizon.client.mixin.world;

import dev.horizon.client.module.world.TpsSync;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * tps-sync's cancel of the player tick (when PlayerUpdate is cancelled).
 * Placed at the first call after the client-loaded check, so the PlayerUpdate event at HEAD has run already.
 */
@Mixin(LocalPlayer.class)
public abstract class TpsSyncLocalPlayerMixin {
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/TickThrottler;tick()V"), cancellable = true)
	private void horizon$tpsSyncSkip(CallbackInfo ci) {
		if (TpsSync.takeSkip()) ci.cancel();
	}
}
