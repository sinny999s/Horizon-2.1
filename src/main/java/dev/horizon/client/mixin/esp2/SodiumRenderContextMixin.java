package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.XRayState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * X-ray with Sodium: the blocks x-ray shows (the only ones Sodium still builds, see
 * SodiumBlockRendererMixin) draw every face, so an ore against stone shows all its sides.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumRenderContextMixin {
	@Inject(method = "isFaceCulled", at = @At("HEAD"), cancellable = true, require = 0)
	private void horizon$xray(Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (XRayState.active) cir.setReturnValue(false);
	}
}
