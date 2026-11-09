package dev.horizon.client.mixin.rage1;

import dev.horizon.client.event.AfterElytraEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.PreElytraEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Around the local player's `LivingEntity.travel` (Pre/AfterElytraEvent); cancelling moves by the event's vector. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void horizon$preTravel(Vec3 input, CallbackInfo ci) {
		LocalPlayer p = Minecraft.getInstance().player;
		if ((Object) this != p) return;
		PreElytraEvent e = EventBus.INSTANCE.post(new PreElytraEvent(p.getDeltaMovement()));
		if (!e.getCancelled()) return;
		p.move(MoverType.SELF, e.getVec());
		ci.cancel();
	}

	@Inject(method = "travel", at = @At("RETURN"), cancellable = true)
	private void horizon$postTravel(Vec3 input, CallbackInfo ci) {
		LocalPlayer p = Minecraft.getInstance().player;
		if ((Object) this != p) return;
		AfterElytraEvent e = EventBus.INSTANCE.post(new AfterElytraEvent(p.getDeltaMovement()));
		if (!e.getCancelled()) return;
		p.move(MoverType.SELF, e.getVec());
		ci.cancel();
	}
}
