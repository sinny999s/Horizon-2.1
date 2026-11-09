package dev.horizon.client.mixin.player2;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The horse jump charge. */
@Mixin(LocalPlayer.class)
public interface JumpRidingAccessor {
	@Accessor("jumpRidingScale")
	void horizonP2_setJumpRidingScale(float scale);
}
