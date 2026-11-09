package dev.horizon.client.mixin.rage1;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rewrites a movement packet's on-ground flag. */
@Mixin(ServerboundMovePlayerPacket.class)
public interface MovePacketAccessor {
	@Mutable
	@Accessor("onGround")
	void horizon_setOnGround(boolean onGround);
}
