package dev.horizon.client.mixin.esp2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.FreecamStateEvent;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** FreecamStateEvent may move and turn the camera. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void horizon$rotation(Args args) {
		FreecamStateEvent e = EventBus.INSTANCE.post(new FreecamStateEvent());
		if (e.getCancelled()) args.setAll(e.getYaw(), e.getPitch());
	}

	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
	private void horizon$position(Args args) {
		FreecamStateEvent e = EventBus.INSTANCE.post(new FreecamStateEvent());
		if (e.getCancelled()) args.setAll(e.getX(), e.getY(), e.getZ());
	}
}
