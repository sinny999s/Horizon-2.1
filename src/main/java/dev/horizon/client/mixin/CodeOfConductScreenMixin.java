package dev.horizon.client.mixin;

import dev.horizon.client.gui.menu.ConnectMenu;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.CodeOfConductScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server's code of conduct, shown while joining, keeps ticking the connecting screen under it
 * (which drives the connection) only when that is vanilla's ConnectScreen; with the client's {@code ConnectMenu} in its
 * place, this ticks that one instead.
 */
@Mixin(CodeOfConductScreen.class)
public abstract class CodeOfConductScreenMixin {
	@Shadow
	@Final
	private Screen parent;

	@Inject(method = "tick", at = @At("TAIL"))
	private void horizon$tickConnectMenu(CallbackInfo ci) {
		if (parent instanceof ConnectMenu) parent.tick();
	}
}
