package dev.horizon.client.mixin;

import dev.horizon.client.event.GlowEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Glow ESP's colour for the entities it picks: vanilla draws the glowing outline in the team colour. */
@Mixin(Entity.class)
public abstract class GlowEntityMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void horizon$glowColor(CallbackInfoReturnable<Integer> cir) {
		GlowEvent e = GlowEvent.INSTANCE.ask((Entity) (Object) this);
		if (e.getGlow()) cir.setReturnValue(e.getColor());
	}
}
