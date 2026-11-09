package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.XRayBlockEvent;
import dev.horizon.client.event.XRayState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * X-ray with Sodium: Sodium builds chunks with its own renderer, which never asks
 * `Block.shouldRenderFace`, so the blocks x-ray doesn't show are skipped here whole. Only applied when Sodium is there.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
	@Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, require = 0)
	private void horizon$xray(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		if (XRayState.active && !EventBus.INSTANCE.post(new XRayBlockEvent(state.getBlock())).getCancelled()) ci.cancel();
	}
}
