package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.InputEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keys go through InputEvent before vanilla, while in a world. */
@Mixin(KeyboardHandler.class)
public abstract class KeyInputMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void horizon$input(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.level == null || mc.player == null) return;
		InputEvent.Action a = action == 0 ? InputEvent.Action.Release : action == 1 ? InputEvent.Action.Press : InputEvent.Action.Hold;
		if (EventBus.INSTANCE.post(new InputEvent(event.key(), InputEvent.Device.Keyboard, a)).getCancelled()) ci.cancel();
	}
}
