package dev.horizon.client.mixin.equip;

import dev.horizon.client.event.BreakDeltaEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cancelled BreakDeltaEvent replaces the progress per tick. */
@Mixin(BlockBehaviour.class)
public abstract class DestroyProgressMixin {
	@Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
	private void horizon$destroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		BreakDeltaEvent e = EventBus.INSTANCE.post(new BreakDeltaEvent(pos, state));
		if (e.getCancelled()) cir.setReturnValue(e.getDelta());
	}
}
