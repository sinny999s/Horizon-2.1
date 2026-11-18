package dev.horizon.client.mixin.perf;

import dev.horizon.client.render.perf.ParticleLight;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the Particle Light Cache ({@link ParticleLight}) when the world's light may have changed: ClientLevel.update
 * (every frame) applies queued light data (chunks, light packets) and runs the light engine (block changes), and both
 * only change anything when there is something to do. Every game tick moves the epoch on too, so nothing is ever kept
 * longer than a tick.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelLightMixin {
	@Shadow
	@Final
	private Deque<Runnable> lightUpdateQueue;

	@Inject(method = "update()V", at = @At("HEAD"))
	private void horizon$queuedLight(CallbackInfo ci) {
		if (!this.lightUpdateQueue.isEmpty()) ParticleLight.changed();
	}

	@Redirect(method = "update()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
	private int horizon$ranLight(LevelLightEngine engine) {
		int count = engine.runLightUpdates();
		if (count > 0) ParticleLight.changed();
		return count;
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
	private void horizon$tick(BooleanSupplier haveTime, CallbackInfo ci) {
		ParticleLight.changed();
	}
}
