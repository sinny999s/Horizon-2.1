package dev.horizon.client.mixin;

import dev.horizon.client.module.cosmetic.BetterChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Better Chat (not in the original): the input bar and its command suggestions in the client's font, and the bar's black
 * fill taken for a rounded pane.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Redirect(method = "init", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;fontFilterFishy:Lnet/minecraft/client/gui/Font;"))
	private Font horizon$inputFont(Minecraft mc) {
		Font base = mc.fontFilterFishy;
		return BetterChat.inputFont(base, ((FontAccessor) base).horizon_provider());
	}

	@Redirect(method = "init", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/ChatScreen;font:Lnet/minecraft/client/gui/Font;"))
	private Font horizon$suggestionsFont(ChatScreen screen) {
		Font base = Minecraft.getInstance().font;
		return BetterChat.inputFont(base, ((FontAccessor) base).horizon_provider());
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void horizon$inputPane(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		if (!BetterChat.inputFill(x0, y0, x1, y1)) graphics.fill(x0, y0, x1, y1, color);
	}
}
