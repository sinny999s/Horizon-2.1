package dev.horizon.client.mixin;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.JumpEvent;
import dev.horizon.client.event.SprintEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sprint changes go through SprintEvent (any living entity, on purpose); the local player's jump through JumpEvent. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
	private boolean horizon$sprint(boolean sprinting) {
		return EventBus.INSTANCE.post(new SprintEvent(sprinting)).getSprinting();
	}

	@Shadow
	protected abstract float getJumpPower();

	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void horizon$jump(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self != Minecraft.getInstance().player) return;
		JumpEvent e = EventBus.INSTANCE.post(new JumpEvent(self.getYRot(), getJumpPower(), 0.2F));
		if (!e.getCancelled()) return;
		ci.cancel();
		// A replay of vanilla's jump with the event's yaw (Math.sin/cos in double, 0.2F).
		float y = e.getY();
		if (y > 1.0E-5F) {
			Vec3 v = self.getDeltaMovement();
			self.setDeltaMovement(v.x, Math.max((double) y, v.y), v.z);
			if (self.isSprinting()) {
				float a = e.getYaw() * 0.017453292F;
				self.addDeltaMovement(new Vec3(-Math.sin((double) a) * (double) e.getXz(), 0.0, Math.cos((double) a) * (double) e.getXz()));
			}
			self.needsSync = true;
		}
	}
}
