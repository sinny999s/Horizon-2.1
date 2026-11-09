package dev.horizon.client.mixin;

import dev.horizon.client.event.GlowEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities the Glow ESP picks glow, as if from a spectral arrow. */
@Mixin(Minecraft.class)
public abstract class GlowMinecraftMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
	private void horizon$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && GlowEvent.INSTANCE.ask(entity).getGlow()) cir.setReturnValue(true);
	}
}
