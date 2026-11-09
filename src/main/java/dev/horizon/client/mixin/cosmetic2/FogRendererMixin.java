package dev.horizon.client.mixin.cosmetic2;

import dev.horizon.client.event.ApplyFogEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every fog value written to the fog buffer can be replaced by ApplyFogEvent. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Unique
	private static final String UPDATE = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V";

	@Unique
	private static ApplyFogEvent horizon$post() {
		return EventBus.INSTANCE.post(new ApplyFogEvent());
	}

	@ModifyVariable(method = UPDATE, at = @At("HEAD"), argsOnly = true)
	private Vector4f horizon$color(Vector4f color) {
		if (color.w == 0f) return color;
		ApplyFogEvent e = horizon$post();
		return e.getCancelled() ? e.getColor() : color;
	}

	@ModifyVariable(method = UPDATE, at = @At("HEAD"), argsOnly = true, index = 4)
	private float horizon$envStart(float v) {
		ApplyFogEvent e = horizon$post();
		return e.getCancelled() ? e.getEnvStart() : v;
	}

	@ModifyVariable(method = UPDATE, at = @At("HEAD"), argsOnly = true, index = 5)
	private float horizon$envEnd(float v) {
		ApplyFogEvent e = horizon$post();
		return e.getCancelled() ? e.getEnvEnd() : v;
	}

	@ModifyVariable(method = UPDATE, at = @At("HEAD"), argsOnly = true, index = 6)
	private float horizon$renderStart(float v) {
		ApplyFogEvent e = horizon$post();
		return e.getCancelled() ? e.getRenderStart() : v;
	}

	@ModifyVariable(method = UPDATE, at = @At("HEAD"), argsOnly = true, index = 7)
	private float horizon$renderEnd(float v) {
		ApplyFogEvent e = horizon$post();
		return e.getCancelled() ? e.getRenderEnd() : v;
	}
}
