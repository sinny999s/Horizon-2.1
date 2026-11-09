package dev.horizon.client.mixin;

import dev.horizon.client.event.SkinEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The skin a player is drawn with, open to the Cape module. */
@Mixin(AbstractClientPlayer.class)
public abstract class SkinMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void horizon$skin(CallbackInfoReturnable<PlayerSkin> cir) {
		cir.setReturnValue(SkinEvent.INSTANCE.ask((AbstractClientPlayer) (Object) this, cir.getReturnValue()));
	}
}
