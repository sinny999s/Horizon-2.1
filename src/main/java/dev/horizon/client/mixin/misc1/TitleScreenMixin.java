package dev.horizon.client.mixin.misc1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.TitleScreenInitEvent;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TitleScreenInitEvent after the title screen's init (used by the RPC first-start prompt). */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Inject(method = "init", at = @At("RETURN"))
	private void horizon$postInit(CallbackInfo ci) {
		TitleScreenInitEvent.INSTANCE.setCancelled(false);
		EventBus.INSTANCE.post(TitleScreenInitEvent.INSTANCE);
	}
}
