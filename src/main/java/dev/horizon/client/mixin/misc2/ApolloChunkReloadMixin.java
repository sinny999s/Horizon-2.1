package dev.horizon.client.mixin.misc2;

import dev.horizon.client.lunar.ApolloEnrichment;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Apollo server rules' `disable-chunk-reloading`: F3+A isn't handled, as Lunar 26.x blocks it (lunar/ApolloEnrichment). */
@Mixin(KeyboardHandler.class)
public abstract class ApolloChunkReloadMixin {
	@Inject(method = "handleDebugKeys", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;allChanged()V"), cancellable = true)
	private void horizon$apolloNoChunkReloading(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (ApolloEnrichment.noChunkReloading()) cir.setReturnValue(false);
	}
}
