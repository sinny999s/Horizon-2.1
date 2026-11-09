package dev.horizon.client.mixin;

import dev.horizon.client.module.cosmetic.BetterChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Better Chat: the black strip behind each chat line goes into its pane instead, without the tag strips. */
@Mixin(targets = {
	"net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess",
	"net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess"
})
public abstract class ChatFillMixin {
	@Shadow(remap = false) @Final private GuiGraphicsExtractor graphics;

	@Inject(method = "fill", at = @At("HEAD"), cancellable = true, remap = false)
	private void horizon$pane(int x0, int y0, int x1, int y1, int color, CallbackInfo ci) {
		if (BetterChat.lineFill(graphics.pose(), x0, y0, x1, y1, color)) ci.cancel();
	}

	/** A message's tag strip (system, not secure, modified) would stick out of the pane's rounded edge: left out with it. */
	@Inject(method = "handleTag", at = @At("HEAD"), cancellable = true, remap = false)
	private void horizon$noTag(CallbackInfo ci) {
		if (BetterChat.hidesTags()) ci.cancel();
	}
}
