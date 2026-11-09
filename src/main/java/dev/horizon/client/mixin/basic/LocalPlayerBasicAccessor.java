package dev.horizon.client.mixin.basic;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Y of the last position sent to the server. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerBasicAccessor {
	@Accessor("yLast")
	double horizon_yLast();
}
