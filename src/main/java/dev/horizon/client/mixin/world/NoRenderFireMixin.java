package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.RenderFireOnEntityEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The flames drawn on a burning entity. */
@Mixin(EntityRenderDispatcher.class)
public abstract class NoRenderFireMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private <S extends EntityRenderState> void horizon$fire(S state, CameraRenderState camera, double x, double y, double z, PoseStack pose,
	                                                       SubmitNodeCollector collector, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new RenderFireOnEntityEvent()).getCancelled()) state.displayFireAnimation = false;
	}
}
