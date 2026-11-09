package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apollo server rules' competitive commands (`/hub`...): in a competitive game they wait for the warning (lunar/ApolloEnrichment). */
@Mixin(ClientPacketListener.class)
public abstract class ApolloCommandMixin {
	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void horizon$apolloCompetitiveCommand(String command, CallbackInfo ci) {
		if (ApolloEnrichment.holdCommand(command, null, false)) ci.cancel();
	}

	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void horizon$apolloCompetitiveUnattended(String command, Screen screen, CallbackInfo ci) {
		if (ApolloEnrichment.holdCommand(command, screen, true)) ci.cancel();
	}
}
