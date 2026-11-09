package dev.horizon.client.mixin.rage1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SlipperinessEvent;
import dev.horizon.client.event.VelocityMultiplierEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block's speed factor and friction; cancelling the event gives dirt's. */
@Mixin(Block.class)
public abstract class BlockSlowdownMixin {
	@Inject(method = "getSpeedFactor", at = @At("HEAD"), cancellable = true)
	private void horizon$speedFactor(CallbackInfoReturnable<Float> cir) {
		if (EventBus.INSTANCE.post(new VelocityMultiplierEvent((Block) (Object) this)).getCancelled()) cir.setReturnValue(Blocks.DIRT.getSpeedFactor());
	}

	@Inject(method = "getFriction", at = @At("HEAD"), cancellable = true)
	private void horizon$friction(CallbackInfoReturnable<Float> cir) {
		if (EventBus.INSTANCE.post(new SlipperinessEvent((Block) (Object) this)).getCancelled()) cir.setReturnValue(Blocks.DIRT.getFriction());
	}
}
