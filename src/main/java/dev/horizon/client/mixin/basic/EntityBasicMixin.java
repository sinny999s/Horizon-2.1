package dev.horizon.client.mixin.basic;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.horizon.client.event.EntityPushEvent;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SetPoseEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The local player's pose, and both pushes inside its `push(Entity)` (their arguments can be zeroed). */
@Mixin(Entity.class)
public abstract class EntityBasicMixin {
	@WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
	private void horizon$push(Entity target, double x, double y, double z, Operation<Void> original) {
		if ((Object) this == Minecraft.getInstance().player && EventBus.INSTANCE.post(new EntityPushEvent()).getCancelled()) {
			original.call(target, 0.0, 0.0, 0.0);
		} else {
			original.call(target, x, y, z);
		}
	}
}
