package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse buttons go through InputEvent before vanilla. */
@Mixin(MouseHandler.class)
public abstract class MouseInputMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void horizon$input(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (handle != Minecraft.getInstance().getWindow().handle()) return;
		InputEvent.Action a = action == 0 ? InputEvent.Action.Release : InputEvent.Action.Press;
		if (EventBus.INSTANCE.post(new InputEvent(info.button(), InputEvent.Device.Mouse, a)).getCancelled()) ci.cancel();
	}
}
