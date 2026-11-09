package dev.horizon.client.mixin.cosmetic2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.ShowFloatingItemEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The floating-item hook (26.2 `GameRenderer.displayItemActivation`; 26.3 moved it to the local player). */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerActivationMixin {
	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
	private void horizon$floatingItem(ItemStack item, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new ShowFloatingItemEvent(item)).getCancelled()) ci.cancel();
	}
}
