package dev.horizon.client.mixin.basic;

import dev.horizon.client.event.AfterElytraEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.FallFlyingEvent;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player's travel (Pre/AfterElytraEvent) and its isFallFlying answer. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBasicMixin {
	@Inject(method = "isFallFlying", at = @At("TAIL"), cancellable = true)
	private void horizon$fallFlying(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this != Minecraft.getInstance().player) return;
		FallFlyingEvent e = EventBus.INSTANCE.post(new FallFlyingEvent(cir.getReturnValueZ()));
		if (e.getCancelled()) cir.setReturnValue(e.getValue());
	}
}
