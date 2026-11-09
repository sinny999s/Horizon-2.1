package dev.horizon.client.mixin;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.KeyboardInputEvent;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After the keys were read into the movement input. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void horizon$input(CallbackInfo ci) {
		EventBus.INSTANCE.post(new KeyboardInputEvent());
	}
}
