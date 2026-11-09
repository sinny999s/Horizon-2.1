package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.XRayState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No ambient-occlusion darkening while x-ray is on. */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {
	@Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
	private void horizon$shade(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (XRayState.active) cir.setReturnValue(1.0F);
	}
}
