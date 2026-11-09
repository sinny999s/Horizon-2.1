package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.XRayState;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While x-ray is on no block occludes, so cave culling hides no section. */
@Mixin(VisGraph.class)
public abstract class VisGraphMixin {
	@Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
	private void horizon$setOpaque(BlockPos pos, CallbackInfo ci) {
		if (XRayState.active) ci.cancel();
	}
}
