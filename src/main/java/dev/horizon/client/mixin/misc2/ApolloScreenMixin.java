package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen changes that went through, for Apollo packet enrichment's inventory open and close (lunar/ApolloEnrichment). The
 * screen before is kept per call (closing a screen sets one again inside the first call).
 */
@Mixin(Gui.class)
public abstract class ApolloScreenMixin {
	@Shadow
	public abstract Screen screen();

	@Inject(method = "setScreen", at = @At("HEAD"))
	private void horizon$apolloScreenBefore(Screen screen, CallbackInfo ci, @Share("apolloOld") LocalRef<Screen> old) {
		old.set(screen());
	}

	@Inject(method = "setScreen", at = @At("RETURN"))
	private void horizon$apolloScreenAfter(Screen screen, CallbackInfo ci, @Share("apolloOld") LocalRef<Screen> old) {
		ApolloEnrichment.screenChanged(old.get(), screen());
	}
}
