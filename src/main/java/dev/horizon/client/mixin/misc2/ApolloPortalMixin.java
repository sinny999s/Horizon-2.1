package dev.horizon.client.mixin.misc2;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Apollo server rules' `anti-portal-traps`: standing in a portal doesn't close the chat, as in Lunar 26.x (lunar/ApolloEnrichment). */
@Mixin(LocalPlayer.class)
public abstract class ApolloPortalMixin {
	@WrapWithCondition(method = "handlePortalTransitionEffect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private boolean horizon$apolloAntiPortalTraps(Gui gui, Screen screen) {
		return !ApolloEnrichment.keepInPortal();
	}
}
