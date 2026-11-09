package dev.horizon.client.mixin.esp1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.MouseButtonEvent;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** InputEvent for mouse buttons. */
@Mixin(MouseHandler.class)
public abstract class MouseButtonMixin {
	@Inject(method = "onButton", at = @At("HEAD"))
	private void horizon$esp1Button(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		EventBus.INSTANCE.post(new MouseButtonEvent(info.button(), action));
	}
}
