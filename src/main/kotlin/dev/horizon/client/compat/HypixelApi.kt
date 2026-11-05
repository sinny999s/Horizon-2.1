package dev.horizon.client.compat

import dev.horizon.client.HorizonClient
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.TickEvent
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.UUID

/**
 * Not in the original: what Hypixel says about you, from its official Mod API (the "Hypixel Mod API" mod, id
 * `hypixel-mod-api`) when that's installed. [location]: Hypixel sends it on every server switch (the server, its game,
 * the lobby's name in a lobby, a game's mode and map). [party]: asked for on every server switch and a few seconds
 * after a party message in chat (someone joined, left, was kicked...), no more often than every [PARTY_GAP] ticks.
 * Without the mod (or off Hypixel) both stay empty. The Mod API's classes are only touched in [Hook], which is loaded
 * only when the mod is there.
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

    val installed: Boolean by lazy { FabricLoader.getInstance().isModLoaded("hypixel-mod-api") }

    private val mc: Minecraft get() = Minecraft.getInstance()
    /** Ticks until the party is asked for (-1: not due). */
    private var partyIn = -1
    private var lastAsked = -PARTY_GAP

    fun init() {
        if (!installed) return
        try {
            Hook.install()
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
                Hook.askParty()
            }
        } catch (e: Throwable) {
            HorizonClient.LOG.warn("Couldn't hook the Hypixel Mod API", e)
        }
    }

    /** Ask Hypixel for the party in [delay] ticks (or as soon after as the gap allows). */
    private fun askParty(delay: Int) {
        if (partyIn < 0 || partyIn > delay) partyIn = delay
    }

    private object Hook {
        fun install() {
            val api = net.hypixel.modapi.HypixelModAPI.getInstance()
            api.subscribeToEventPacket(net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket::class.java)
            api.createHandler(net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket::class.java) { pk ->
                location = Location(pk.serverName, pk.serverType.map { it.name() }.orElse(null), pk.lobbyName.orElse(null),
                    pk.mode.orElse(null), pk.map.orElse(null))
                HorizonClient.LOG.info("Hypixel location: {}", location)
                askParty(0)
            }
            api.createHandler(net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket::class.java) { pk ->
                val me = mc.player?.uuid
                party = if (pk.isInParty) pk.members.filterTo(HashSet()) { it != me } else emptySet()
                HorizonClient.LOG.info("Hypixel party: {} other member(s)", party.size)
            }
        }

        fun askParty() {
            net.hypixel.modapi.HypixelModAPI.getInstance().sendPacket(net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket())
        }
    }

    /** Words in Hypixel's party messages that mean the party changed. */
    private val PARTY_WORDS = listOf("joined", "left", "removed", "kicked", "disbanded", "transferred", "promoted", "demoted")
    private const val PARTY_GAP = 100
    private const val PARTY_DELAY = 40
}
