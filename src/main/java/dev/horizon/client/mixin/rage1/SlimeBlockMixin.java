package dev.horizon.client.mixin.rage1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.StepOnSlimeEvent;
import net.minecraft.world.level.block.SlimeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Walking on slime (for any entity, on purpose). */
@Mixin(SlimeBlock.class)
public abstract class SlimeBlockMixin {
	@Inject(method = "stepOn", at = @At("HEAD"), cancellable = true)
	private void horizon$stepOn(CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new StepOnSlimeEvent()).getCancelled()) ci.cancel();
	}
}
