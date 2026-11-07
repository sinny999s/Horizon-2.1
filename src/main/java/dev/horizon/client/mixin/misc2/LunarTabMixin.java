package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.horizon.client.lunar.LunarNameTag;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not in the original: Lunar Spoof's tab list logo (Moonlight's GuiPlayerTabOverlayMixin). A Lunar player's name is
 * drawn after Lunar's logo (tinted their logo colour, with the Lunar+ plus) and badge ([LunarNameTag]), and counts as
 * that much wider when the columns are sized.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class LunarTabMixin {
	@ModifyExpressionValue(method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I", ordinal = 0))
	private int horizon$lunarNameWidth(int width, @Local PlayerInfo info) {
		return width + LunarNameTag.tabShift(info.getProfile().id());
	}

	@WrapOperation(method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void horizon$lunarLogo(GuiGraphicsExtractor graphics, Font font, Component name, int x, int y, int color, Operation<Void> original,
	                               @Local PlayerInfo info) {
		original.call(graphics, font, name, x + LunarNameTag.drawTab(graphics, x, y, info.getProfile().id()), y, color);
	}
}
