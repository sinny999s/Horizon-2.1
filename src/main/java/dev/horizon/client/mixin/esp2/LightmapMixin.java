package dev.horizon.client.mixin.esp2;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.LightMapUpdateEvent;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A cancelled LightMapUpdateEvent makes the lightmap plain white. */
@Mixin(Lightmap.class)
public abstract class LightmapMixin {
	@Shadow
	@Final
	private GpuTexture texture;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void horizon$render(LightmapRenderState state, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new LightMapUpdateEvent()).getCancelled()) {
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f(1.0F));
			ci.cancel();
		}
	}
}
