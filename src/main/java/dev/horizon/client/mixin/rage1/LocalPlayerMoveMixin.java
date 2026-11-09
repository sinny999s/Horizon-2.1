package dev.horizon.client.mixin.rage1;

import com.mojang.authlib.GameProfile;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.MoveEvent;
import dev.horizon.client.event.ShouldStopSprintingEvent;
import dev.horizon.client.event.SlowDownEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The local player's move (the call to `super.move`; cancelling moves by the event's vector instead, with
 * the walked distance but no auto-jump), the sneak/crawl slowdown and the sprint stop.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMoveMixin extends AbstractClientPlayer {
	public LocalPlayerMoveMixin(ClientLevel level, GameProfile profile) {
		super(level, profile);
	}

	@Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), cancellable = true)
	private void horizon$move(MoverType type, Vec3 movement, CallbackInfo ci) {
		MoveEvent e = EventBus.INSTANCE.post(new MoveEvent(type, movement));
		if (!e.getCancelled()) return;
		double x = getX();
		double z = getZ();
		super.move(type, new Vec3(e.getX(), e.getY(), e.getZ()));
		addWalkedDistance(Mth.length((float) (getX() - x), (float) (getZ() - z)) * 0.6F);
		ci.cancel();
	}

	@Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
	private void horizon$slowDown(CallbackInfoReturnable<Boolean> cir) {
		if (EventBus.INSTANCE.post(new SlowDownEvent()).getCancelled()) cir.setReturnValue(false);
	}

	@Inject(method = "shouldStopRunSprinting", at = @At("HEAD"), cancellable = true)
	private void horizon$stopSprinting(CallbackInfoReturnable<Boolean> cir) {
		if (EventBus.INSTANCE.post(new ShouldStopSprintingEvent()).getCancelled()) cir.setReturnValue(false);
	}
}
