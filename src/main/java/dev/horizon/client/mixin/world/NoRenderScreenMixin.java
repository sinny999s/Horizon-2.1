package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderGuiBackgroundEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The dimmed in-game background behind a screen. */
@Mixin(Screen.class)
public abstract class NoRenderScreenMixin {
	@Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void horizon$background(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderGuiBackgroundEvent()).getCancelled()) ci.cancel();
	}
}
