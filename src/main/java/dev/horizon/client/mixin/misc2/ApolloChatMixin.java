package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Apollo packet enrichment's chat open and close messages, at the end of ChatScreen.init and removed as Lunar Client
 * 26.x sends them, and server rules' chat length (lunar/ApolloEnrichment).
 */
@Mixin(ChatScreen.class)
public abstract class ApolloChatMixin {
	@Shadow
	protected EditBox input;

	@Inject(method = "init", at = @At("TAIL"))
	private void horizon$apolloChatOpen(CallbackInfo ci) {
		Integer max = ApolloEnrichment.maxChatLength();
		if (max != null && input != null) input.setMaxLength(max);
		ApolloEnrichment.chat(true);
	}

	@Inject(method = "removed", at = @At("TAIL"))
	private void horizon$apolloChatClose(CallbackInfo ci) {
		ApolloEnrichment.chat(false);
	}
}
