package dev.horizon.client.mixin;

import dev.horizon.client.gui.menu.MainMenu;
import dev.horizon.client.render.Backdrop;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every screen that would show the panorama shows the client's animated backdrop instead. */
@Mixin(Screen.class)
public abstract class MenuPanoramaMixin {
	@Shadow public int width;
	@Shadow public int height;

	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void horizon$backdrop(GuiGraphicsExtractor graphics, float delta, CallbackInfo ci) {
		Backdrop.INSTANCE.draw(graphics, width, height, !((Object) this instanceof MainMenu));
		ci.cancel();
	}
}
