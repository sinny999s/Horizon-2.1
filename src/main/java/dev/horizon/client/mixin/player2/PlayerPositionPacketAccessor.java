package dev.horizon.client.mixin.player2;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rewrite a teleport's position and rotation. */
@Mixin(ClientboundPlayerPositionPacket.class)
public interface PlayerPositionPacketAccessor {
	@Mutable
	@Accessor("change")
	void horizonP2_setChange(PositionMoveRotation change);
}
