package dev.horizon.client.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.MapRenderEvent;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A map's contents; only item frames pass `active`, so maps in hand are kept. */
@Mixin(MapRenderer.class)
public abstract class NoRenderMapMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void horizon$map(MapRenderState state, PoseStack pose, SubmitNodeCollector collector, boolean active, int light, CallbackInfo ci) {
		if (active && EventBus.INSTANCE.post(new MapRenderEvent()).getCancelled()) ci.cancel();
	}
}
