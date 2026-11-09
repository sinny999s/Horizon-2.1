package dev.horizon.client.mixin.basic;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The jump cooldown. */
@Mixin(LivingEntity.class)
public interface LivingEntityBasicAccessor {
	@Accessor("noJumpDelay")
	void horizon_setNoJumpDelay(int ticks);
}
