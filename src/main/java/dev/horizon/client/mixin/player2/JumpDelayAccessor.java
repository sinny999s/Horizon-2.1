package dev.horizon.client.mixin.player2;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hold-to-rejump cooldown. */
@Mixin(LivingEntity.class)
public interface JumpDelayAccessor {
	@Accessor("noJumpDelay")
	int horizonP2_noJumpDelay();

	@Accessor("noJumpDelay")
	void horizonP2_setNoJumpDelay(int delay);
}
