package dev.horizon.client.mixin;

import dev.horizon.client.module.misc.AutoReconnect;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Reconnect's button under the back button, before the layout is arranged. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
	@Shadow @Final private Screen parent;
	@Shadow @Final private DisconnectionDetails details;
	@Shadow @Final private LinearLayout layout;

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V"))
	private void horizon$reconnect(CallbackInfo ci) {
		layout.addChild(AutoReconnect.button(parent, details.reason()));
	}
}
