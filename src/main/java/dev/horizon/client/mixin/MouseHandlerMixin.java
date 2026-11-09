package dev.horizon.client.mixin;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.MouseTurnEvent;
import dev.horizon.client.hud.Hud;
import dev.horizon.client.module.Binds;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HUD widget dragging, mouse binds and the held-buttons list, before the screen sees the click. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "onButton", at = @At("HEAD"))
	private void horizon$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (action == 1 || action == 0) Hud.INSTANCE.mouse(info.button(), action == 1);
		Binds.INSTANCE.onMouse(info.button(), action);
	}

	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void horizon$recordPhysicalMove(CallbackInfo ci) {
		if (this.accumulatedDX != 0.0 || this.accumulatedDY != 0.0) {
			MouseTurnEvent.INSTANCE.setLastPhysicalMoveNs(System.nanoTime());
		}
	}

	/** A turn of the camera each frame, after the mouse's own (MouseTurnEvent). */
	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void horizon$turn(CallbackInfo ci) {
		EventBus.INSTANCE.post(MouseTurnEvent.INSTANCE);
	}
}
