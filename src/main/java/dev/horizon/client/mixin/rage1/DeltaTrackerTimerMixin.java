package dev.horizon.client.mixin.rage1;

import dev.horizon.client.combat.TimerManager;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The timer: while the factor isn't 1, a frame's milliseconds become ticks at the raw 50 ms per
 * tick times the factor (the server's tick rate is ignored then).
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
	@Shadow
	private float deltaTicks;
	@Shadow
	private float deltaTickResidual;
	@Shadow
	private long lastMs;
	@Shadow
	@Final
	private float msPerTick;

	@Inject(method = "advanceGameTime(J)I", at = @At("HEAD"), cancellable = true)
	private void horizon$timer(long currentMs, CallbackInfoReturnable<Integer> cir) {
		float factor = TimerManager.getFactor();
		if (factor == 1.0F) return;
		deltaTicks = (float) (currentMs - lastMs) / msPerTick * factor;
		lastMs = currentMs;
		deltaTickResidual = deltaTickResidual + deltaTicks;
		int ticks = (int) deltaTickResidual;
		deltaTickResidual -= ticks;
		cir.setReturnValue(ticks);
	}
}
