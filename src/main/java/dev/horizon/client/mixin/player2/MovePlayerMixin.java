package dev.horizon.client.mixin.player2;

import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.FlagEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server teleport, once applied (main thread). */
@Mixin(ClientPacketListener.class)
public abstract class MovePlayerMixin {
	@Inject(method = "handleMovePlayer", at = @At("RETURN"))
	private void horizon$flag(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		EventBus.INSTANCE.post(new FlagEvent(packet));
	}
}
