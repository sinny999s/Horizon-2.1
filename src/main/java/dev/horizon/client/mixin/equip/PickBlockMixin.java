package dev.horizon.client.mixin.equip;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.PickBlockEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block picks go through PickBlockEvent (cancellable). */
@Mixin(MultiPlayerGameMode.class)
public abstract class PickBlockMixin {
	@Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
	private void horizon$pickBlock(BlockPos pos, boolean includeData, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new PickBlockEvent()).getCancelled()) ci.cancel();
	}
}
