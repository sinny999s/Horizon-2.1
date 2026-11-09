package dev.horizon.client.mixin.world;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderParticleEvent;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every particle added to the engine. */
@Mixin(ParticleEngine.class)
public abstract class NoRenderParticleMixin {
	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
	private void horizon$particle(Particle particle, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderParticleEvent(particle)).getCancelled()) ci.cancel();
	}
}
