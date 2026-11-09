package dev.horizon.client.mixin.player2;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The right-click repeat delay. */
@Mixin(Minecraft.class)
public interface UseDelayAccessor {
	@Accessor("rightClickDelay")
	int horizonP2_rightClickDelay();

	@Accessor("rightClickDelay")
	void horizonP2_setRightClickDelay(int delay);
}
