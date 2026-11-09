package dev.horizon.client.mixin;

import dev.horizon.client.AnarchyServers;
import dev.horizon.client.event.EventBus;
import dev.horizon.client.event.SendMessageEvent;
import dev.horizon.client.event.WorldUpdateEvent;
import dev.horizon.client.hud.ServerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Time packets (TPS) and totem pops for the HUD, on the game thread; outgoing chat lines (client commands); the
 * anarchy-server join message.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

	@Inject(method = "handleSetTime", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
	private void horizon$time(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerInfo.INSTANCE.timePacket();
	}

	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void horizon$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		EventBus.INSTANCE.post(new WorldUpdateEvent());
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void horizon$chunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		EventBus.INSTANCE.post(new WorldUpdateEvent());
	}

	@Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER))
	private void horizon$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		ServerInfo.INSTANCE.entityEvent(packet);
	}

	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void horizon$sendChat(String message, CallbackInfo ci) {
		if (EventBus.INSTANCE.post(new SendMessageEvent(message)).getCancelled()) ci.cancel();
	}

	/** On joining 6b6t (or another anarchy server), the message AnarchyMod sends ({@link AnarchyServers}). */
	@Inject(method = "handleLogin", at = @At("RETURN"))
	private void horizon$anarchyJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
		var server = Minecraft.getInstance().getCurrentServer();
		if (server == null || !AnarchyServers.contains(server.ip)) return;
		try {
			((ClientPacketListener) (Object) this).getConnection().send(AnarchyServers.joinMessage());
		} catch (RuntimeException ignored) {
			// Only a courtesy message.
		}
	}
}
