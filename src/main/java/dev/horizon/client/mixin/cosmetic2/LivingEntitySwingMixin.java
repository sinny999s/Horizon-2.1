package dev.horizon.client.mixin.cosmetic2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SwingDurationEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The swing-duration hook (26.2 `getCurrentSwingDuration`, every entity). 26.3 fixes a swing's length when it
 * starts, in `getModifiedSwingDuration`.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
	@Inject(method = "getModifiedSwingDuration", at = @At("HEAD"), cancellable = true)
	private void horizon$swingDuration(SwingAnimation animation, CallbackInfoReturnable<Integer> cir) {
		SwingDurationEvent e = EventBus.INSTANCE.post(new SwingDurationEvent());
		if (e.getCancelled()) cir.setReturnValue(e.getValue());
	}
}
