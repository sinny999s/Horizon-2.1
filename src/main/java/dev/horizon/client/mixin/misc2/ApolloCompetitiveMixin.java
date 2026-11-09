package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Apollo server rules' `competitive-game`: the pause menu's Disconnect asks first, wrapping the runnable it hands
 * ReportingContext.draftReportHandled as Lunar 26.x does (lunar/ApolloEnrichment). Other disconnects aren't asked.
 */
@Mixin(PauseScreen.class)
public abstract class ApolloCompetitiveMixin {
	@ModifyArg(method = "lambda$createPauseMenu$10", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/chat/report/ReportingContext;draftReportHandled(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/Runnable;Z)V"),
		index = 2)
	private Runnable horizon$apolloCompetitiveDisconnect(Runnable run) {
		return ApolloEnrichment.disconnectButton(run, (PauseScreen) (Object) this);
	}
}
