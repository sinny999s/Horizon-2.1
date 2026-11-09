package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.BlockStateEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block changed in a client chunk (search re-scans that chunk). */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void horizon$setBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (level.isClientSide()) EventBus.INSTANCE.post(new BlockStateEvent(pos, state, cir.getReturnValue()));
	}
}
