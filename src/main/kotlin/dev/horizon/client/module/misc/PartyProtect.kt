package dev.horizon.client.module.misc

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.compat.HypixelApi
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Your Hypixel party counts as friends while this is on, so everything that spares friends (Aura,
 * Trigger Bot, Aim Assist, Auto Clicker, the ESPs' friend colour...) spares them too. The party comes from Hypixel's Mod
 * API ([HypixelApi.party]). A member is matched through the tab list
 * (their name there to their UUID), so a nicked member, whose UUID in a game isn't their real one, isn't matched.
 */
class PartyProtect : Module("party-protect", Categories.MISC, listOf("party-friends", "party")) {
    /** Says in chat how many party members are spared whenever that changes. */
    val notify = bool("notify", true)

    private var told = -1

    init {
        instance = this
        on<TickEvent> {
            val n = HypixelApi.party.size
            if (n == told) return@on
            told = n
            if (notify.value && mc.player != null)
                moduleMessage(name.trim(), if (n == 0) "no party members to spare" else "sparing $n party member${if (n == 1) "" else "s"}")
        }
    }

    override fun onEnable() {
        told = -1
    }

    companion object {
        /** For [dev.horizon.client.config.Friends], which is asked often (every player, every frame for the ESPs). */
        var instance: PartyProtect? = null
            private set
    }

    /** Whether the player named [name] (in the tab list) is in your party. */
    fun covers(name: String): Boolean {
        if (!enabled) return false
        val party = HypixelApi.party
        if (party.isEmpty()) return false
        val id = mc.connection?.getPlayerInfo(name)?.profile?.id ?: return false
        return id in party
    }
}
