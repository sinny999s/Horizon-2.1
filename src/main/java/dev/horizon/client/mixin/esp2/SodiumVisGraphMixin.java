package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.XRayState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VisGraphMixin for Sodium's own cave culling: while x-ray is on no block occludes. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.DirectionalVisGraph", remap = false)
public abstract class SodiumVisGraphMixin {
	@Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true, require = 0)
	private void horizon$setOpaque(int x, int y, int z, CallbackInfo ci) {
		if (XRayState.active) ci.cancel();
	}
}
