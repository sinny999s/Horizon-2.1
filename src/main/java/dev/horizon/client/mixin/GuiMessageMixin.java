package dev.horizon.client.mixin;

import dev.horizon.client.module.cosmetic.BetterChat;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Better Chat: a message's text set in the client's font as vanilla wraps it into lines. */
@Mixin(GuiMessage.class)
public abstract class GuiMessageMixin {
	@ModifyArg(method = "splitLines", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"), index = 0)
	private FormattedText horizon$font(FormattedText content) {
		return BetterChat.restyle(content);
	}
}
