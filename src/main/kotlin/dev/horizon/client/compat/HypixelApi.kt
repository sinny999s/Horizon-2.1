package dev.horizon.client.compat

import dev.horizon.client.HorizonClient
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.TickEvent
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.HypixelModAPIImplementation
import net.hypixel.modapi.error.ErrorReason
import net.hypixel.modapi.packet.ClientboundHypixelPacket
import net.hypixel.modapi.packet.HypixelPacket
import net.hypixel.modapi.packet.impl.clientbound.ClientboundHelloPacket
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import net.hypixel.modapi.serializer.PacketSerializer
import net.minecraft.client.Minecraft
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * Not in the original: what Hypixel says about you, through its official Mod API. Hypixel's protocol library
 * (`net.hypixel:mod-api`) is inside Horizon's jar, and [Connection] carries its packets the way Hypixel's own Fabric mod
 * does (after HypixelDev/FabricModAPI, MIT), so nothing else needs installing. If that mod is installed anyway, it
 * carries them and Horizon only listens: two of them would register the same channels.
 *
 * [location]: Hypixel sends it on every server switch (the server, its game, the lobby's name in a lobby, a game's mode
 * and map). [party]: asked for on every server switch and a few seconds after a party message in chat (someone
 * joined, left, was kicked...), no more often than every [PARTY_GAP] ticks. Off Hypixel both stay empty.
 */
object HypixelApi {
    /** Where Hypixel last said you are. [lobby] is set only in a lobby (not in a game's waiting room). */
    data class Location(val server: String, val type: String?, val lobby: String?, val mode: String?, val map: String?)

    @Volatile
    var location: Location? = null
        private set

    /** Your party's members other than you, by UUID (empty when not in a party, or not known). */
    @Volatile
    var party: Set<UUID> = emptySet()
        private set

    /** Hypixel's own Fabric mod is installed: it carries the packets, not [Connection]. */
    private val externalMod: Boolean by lazy { FabricLoader.getInstance().isModLoaded("hypixel-mod-api") }

    private val mc: Minecraft get() = Minecraft.getInstance()
    /** Ticks until the party is asked for (-1: not due). */
    private var partyIn = -1
    private var lastAsked = -PARTY_GAP

    fun init() {
        try {
            if (!externalMod) HypixelModAPI.getInstance().setModImplementation(Connection)
            val api = HypixelModAPI.getInstance()
            api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
            api.createHandler(ClientboundLocationPacket::class.java) { pk ->
                location = Location(pk.serverName, pk.serverType.map { it.name() }.orElse(null), pk.lobbyName.orElse(null),
                    pk.mode.orElse(null), pk.map.orElse(null))
                HorizonClient.LOG.info("Hypixel location: {}", location)
                askParty(0)
            }
            api.createHandler(ClientboundPartyInfoPacket::class.java) { pk ->
                val me = mc.player?.uuid
                party = if (pk.isInParty) pk.members.filterTo(HashSet()) { it != me } else emptySet()
                HorizonClient.LOG.info("Hypixel party: {} other member(s)", party.size)
            }
            ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
                location = null
                party = emptySet()
            }
            EventBus.on<AfterReceivePacketEvent>(this) { e ->
                val pk = e.packet as? ClientboundSystemChatPacket ?: return@on
                if (pk.overlay()) return@on
                val text = pk.content().string.lowercase()
                if ("party" in text && PARTY_WORDS.any { it in text }) askParty(PARTY_DELAY)
            }
            EventBus.on<TickEvent>(this) {
                val p = mc.player ?: return@on
                if (partyIn < 0) return@on
                if (partyIn > 0 || p.tickCount - lastAsked < PARTY_GAP) {
                    if (partyIn > 0) partyIn--
                    return@on
                }
                partyIn = -1
                lastAsked = p.tickCount
                api.sendPacket(ServerboundPartyInfoPacket())
            }
        } catch (e: Throwable) {
            HorizonClient.LOG.warn("Couldn't set up the Hypixel Mod API", e)
        }
    }

    /** Ask Hypixel for the party in [delay] ticks (or as soon after as the gap allows). */
    private fun askParty(delay: Int) {
        if (partyIn < 0 || partyIn > delay) partyIn = delay
    }

    /**
     * The Mod API's packets on Fabric's networking, as Hypixel's Fabric mod sends them: each of its identifiers a
     * custom payload channel, in play and in configuration. Hypixel answers on a server that knows the mod is there
     * (its hello), and only then is anything sent.
     */
    private object Connection : HypixelModAPIImplementation {
        @Volatile
        private var onHypixel = false

        override fun onInit() {
            HypixelModAPI.getInstance().createHandler(ClientboundHelloPacket::class.java) { onHypixel = true }
            ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onHypixel = false }
            val registry = HypixelModAPI.getInstance().registry
            for (id in registry.clientboundIdentifiers) {
                try {
                    val type = CustomPacketPayload.Type<Clientbound>(Identifier.parse(id))
                    val codec = Clientbound.codec(type)
                    PayloadTypeRegistry.clientboundPlay().register(type, codec)
                    PayloadTypeRegistry.clientboundConfiguration().register(type, codec)
                    ClientPlayNetworking.registerGlobalReceiver(type) { payload, _ -> handle(id, payload) }
                    ClientConfigurationNetworking.registerGlobalReceiver(type) { payload, _ -> handle(id, payload) }
                } catch (_: IllegalArgumentException) {
                    // Already registered.
                }
            }
            for (id in registry.serverboundIdentifiers) {
                try {
                    val type = CustomPacketPayload.Type<Serverbound>(Identifier.parse(id))
                    PayloadTypeRegistry.serverboundPlay().register(type, Serverbound.CODEC)
                    PayloadTypeRegistry.serverboundConfiguration().register(type, Serverbound.CODEC)
                } catch (_: IllegalArgumentException) {
                    // Already registered.
                }
            }
        }

        private fun handle(id: String, payload: Clientbound) {
            try {
                val pk = payload.packet
                if (pk == null) HypixelModAPI.getInstance().handleError(id, payload.error)
                else HypixelModAPI.getInstance().handle(pk)
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Hypixel Mod API: couldn't handle {}", id, e)
            }
        }

        override fun sendPacket(packet: HypixelPacket): Boolean {
            if (!onHypixel) return false
            val payload = Serverbound(packet)
            return try {
                if (Minecraft.getInstance().connection != null) ClientPlayNetworking.send(payload)
                else ClientConfigurationNetworking.send(payload)
                true
            } catch (_: IllegalStateException) {
                false
            }
        }

        override fun isConnectedToHypixel() = onHypixel
    }

    /** A packet from Hypixel: a success flag, then the packet, or the error's id. */
    private class Clientbound(private val type: CustomPacketPayload.Type<Clientbound>, buf: ByteBuf) : CustomPacketPayload {
        var packet: ClientboundHypixelPacket? = null
            private set
        var error: ErrorReason? = null
            private set

        init {
            val s = PacketSerializer(buf)
            if (s.readBoolean()) packet = HypixelModAPI.getInstance().registry.createClientboundPacket(type.id().toString(), s)
            else error = ErrorReason.getById(s.readVarInt())
        }

        override fun type() = type

        companion object {
            fun codec(type: CustomPacketPayload.Type<Clientbound>): StreamCodec<ByteBuf, Clientbound> =
                CustomPacketPayload.codec({ _, _ -> throw UnsupportedOperationException("Hypixel's packets aren't sent") },
                    { buf -> Clientbound(type, buf) })
        }
    }

    /** A packet to Hypixel, written by the library. */
    private class Serverbound(private val packet: HypixelPacket) : CustomPacketPayload {
        private val type = CustomPacketPayload.Type<Serverbound>(Identifier.parse(packet.identifier))

        override fun type() = type

        companion object {
            val CODEC: StreamCodec<ByteBuf, Serverbound> = CustomPacketPayload.codec(
                { value, buf -> value.packet.write(PacketSerializer(buf)) },
                { _ -> throw UnsupportedOperationException("Only Hypixel sends these") })
        }
    }

    /** Words in Hypixel's party messages that mean the party changed. */
    private val PARTY_WORDS = listOf("joined", "left", "removed", "kicked", "disbanded", "transferred", "promoted", "demoted")
    private const val PARTY_GAP = 100
    private const val PARTY_DELAY = 40
}
