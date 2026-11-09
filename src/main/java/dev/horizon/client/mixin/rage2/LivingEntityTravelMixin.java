package dev.horizon.client.mixin.rage2;

import dev.horizon.client.event.ElytraTravelEvent;
import dev.horizon.client.event.EventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Before and after the local player's `travel` (pre / after elytra event). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void horizon$preTravel(Vec3 input, CallbackInfo ci) {
		LocalPlayer p = Minecraft.getInstance().player;
		if ((Object) this != p) return;
		ElytraTravelEvent e = EventBus.INSTANCE.post(new ElytraTravelEvent(true, p.getDeltaMovement()));
		if (e.getCancelled()) {
			p.move(MoverType.SELF, e.getVec());
			ci.cancel();
		}
	}

	@Inject(method = "travel", at = @At("RETURN"))
	private void horizon$postTravel(Vec3 input, CallbackInfo ci) {
		LocalPlayer p = Minecraft.getInstance().player;
		if ((Object) this != p) return;
		ElytraTravelEvent e = EventBus.INSTANCE.post(new ElytraTravelEvent(false, p.getDeltaMovement()));
		if (e.getCancelled()) p.move(MoverType.SELF, e.getVec());
	}
}
