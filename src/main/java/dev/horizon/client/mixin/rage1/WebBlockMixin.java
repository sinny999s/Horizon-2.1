package dev.horizon.client.mixin.rage1;

import dev.horizon.client.event.CobWebEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.world.level.block.WebBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cobweb slowdown (for any entity, on purpose). */
@Mixin(WebBlock.class)
public abstract class WebBlockMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void horizon$cobweb(CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new CobWebEvent()).getCancelled()) ci.cancel();
	}
}
