package dev.horizon.client.mixin.esp2;

import dev.horizon.client.module.esp.XRay;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * x-ray's MoveEvent handler (the move is made with x = z = 0): the deobfuscator stands still
 * while it works through its list. Hooks `super.move` inside `LocalPlayer.move`.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMoveMixin {
	@ModifyArg(method = "move", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/AbstractClientPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
	private Vec3 horizon$xrayFreeze(Vec3 movement) {
		return XRay.freezesMovement() ? new Vec3(0.0, movement.y, 0.0) : movement;
	}
}
