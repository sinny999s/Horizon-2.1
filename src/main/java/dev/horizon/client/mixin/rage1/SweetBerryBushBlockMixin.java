package dev.horizon.client.mixin.rage1;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SweetBerryBushCollisionEvent;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sweet berry bush slowdown and damage (for any entity, on purpose). */
@Mixin(SweetBerryBushBlock.class)
public abstract class SweetBerryBushBlockMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void horizon$berryBush(CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new SweetBerryBushCollisionEvent()).getCancelled()) ci.cancel();
	}
}
