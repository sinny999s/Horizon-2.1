package dev.horizon.client.mixin.perf;

import dev.horizon.client.render.perf.ParticleLight;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Particle Light Cache ({@link ParticleLight}): the world light lookup in Particle.getLightCoords, kept per particle
 * while it stays in the same block and the world's light hasn't changed (the same epoch). A redirect, so the lookup
 * that is skipped costs no allocation; subclasses that change their light per frame on top of it (flames brighten as
 * they age) still do.
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {
	@Unique
	private int horizon$lightEpoch;
	@Unique
	private long horizon$lightPos;
	@Unique
	private int horizon$light;

	@Redirect(method = "getLightCoords(F)I", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/util/LightCoordsUtil;getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I"))
	private int horizon$cachedLight(BlockAndLightGetter level, BlockPos pos) {
		if (!ParticleLight.enabled()) return LightCoordsUtil.getLightCoords(level, pos);
		long key = pos.asLong();
		int epoch = ParticleLight.epoch;
		if (horizon$lightEpoch == epoch && horizon$lightPos == key) {
			if (ParticleLight.verify) ParticleLight.checked(horizon$light, LightCoordsUtil.getLightCoords(level, pos));
			return horizon$light;
		}
		if (ParticleLight.verify) ParticleLight.missed();
		int light = LightCoordsUtil.getLightCoords(level, pos);
		horizon$light = light;
		horizon$lightPos = key;
		horizon$lightEpoch = epoch;
		return light;
	}
}
