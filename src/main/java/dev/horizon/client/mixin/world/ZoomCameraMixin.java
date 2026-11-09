package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.horizon.client.event.BasicProjectionMatrixEvent;
import dev.horizon.client.event.DarknessEffectEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.GetFogModifierEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A cancelled BasicProjectionMatrixEvent rebuilds the level
 * projection. Same hook as the cosmetic1 copy's mixin/cosmetic1/CameraMixin (aspect): keep one when merging.
 */
@Mixin(Camera.class)
public abstract class ZoomCameraMixin {
	@Shadow
	private Entity entity;

	@Shadow
	public abstract float getFov();

	@Shadow
	public abstract FogType getFluidInCamera();

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void horizon$projection(CameraRenderState state, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (state.entityRenderState.doesMobEffectBlockSky && entity instanceof LivingEntity living && getFluidInCamera() == FogType.NONE) {
			boolean blind = living.hasEffect(MobEffects.BLINDNESS) && !EventBus.INSTANCE.post(new GetFogModifierEvent()).getCancelled();
			boolean dark = living.hasEffect(MobEffects.DARKNESS) && !EventBus.INSTANCE.post(new DarknessEffectEvent()).getCancelled();
			state.entityRenderState.doesMobEffectBlockSky = blind || dark;
		}
		BasicProjectionMatrixEvent.Companion.setFov(getFov());
		if (!EventBus.INSTANCE.post(new BasicProjectionMatrixEvent()).getCancelled()) return;
		float zoom = BasicProjectionMatrixEvent.Companion.getZoom();
		float zoomX = BasicProjectionMatrixEvent.Companion.getZoomX();
		float zoomY = BasicProjectionMatrixEvent.Companion.getZoomY();
		float fov = BasicProjectionMatrixEvent.Companion.getFov();
		state.projectionMatrix.identity();
		if (zoom != 1.0F) {
			state.projectionMatrix.translate(zoomX, -zoomY, 0.0F);
			state.projectionMatrix.scale(zoom, zoom, 1.0F);
		}
		state.projectionMatrix.perspective(fov * (float) (Math.PI / 180.0), BasicProjectionMatrixEvent.Companion.getRatio(), state.depthFar,
			0.05F, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
	}
}
