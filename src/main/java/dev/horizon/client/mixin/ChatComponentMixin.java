package dev.horizon.client.mixin;

import dev.horizon.client.module.cosmetic.BetterChat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Better Chat: with chat closed and no screen open, vanilla's chat waits for the client's layer,
 * which draws its pane first (BetterChat.renderLayer); every chat draw also has the line strips it fills counted for the
 * pane.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"), cancellable = true)
	private void horizon$hold(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY, ChatComponent.DisplayMode mode, boolean insertions, CallbackInfo ci) {
		ChatComponent self = (ChatComponent) (Object) this;
		// Drawn again later with the client's layer's graphics, so the text lands over the pane.
		if (BetterChat.hold(layer -> {
			self.extractRenderState(layer, font, ticks, mouseX, mouseY, mode, insertions);
			return kotlin.Unit.INSTANCE;
		})) {
			ci.cancel();
			return;
		}
		BetterChat.beginLines();
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("TAIL"))
	private void horizon$counted(CallbackInfo ci) {
		BetterChat.endLines();
	}
}
