package dev.horizon.client.module.misc

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.moduleChat
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.item.Items

/**
 * Disconnects (or runs [command]) when a non-friend player is within [distance], and
 * disconnects when out of totems or low on health. With [instantLeave] it first sends an invalid hotbar slot.
 */
class AutoLeave : Module("auto-leave", Categories.MISC) {
    @Suppress("EnumEntryName")
    enum class Players { None, Command, Leave }

    val disableSetting = bool("disable", true)
    val instantLeave = bool("InstantLeave", true)
    val hp = bool("hp", false)
    val totems = bool("totems", false)
    val minTotems = int("min-totems", 2, 0..10) { totems.value }
    val minHp = float("min-hp", 8.0f, 1.0f..20.0f) { hp.value }
    val players = enum("players", Players.Leave)
    val command = string("command", "hub") { players.value == Players.Command }
    val distance = int("distance", 256, 4..256) { players.value != Players.None }

    private val commandTimer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            for (other in level.players()) {
                if (other === player || Friends.isFriend(other.gameProfile.name) || players.value == Players.None) continue
                if (player.position().distanceTo(other.position()) > distance.value) continue
                when (players.value) {
                    Players.Command -> {
                        if (commandTimer.passedAndReset(1000)) {
                            if (disableSetting.value) disable()
                            moduleChat(this, PLAYER)
                            player.connection.sendCommand(command.value.replace("/", ""))
                        }
                        return@on
                    }
                    Players.Leave -> {
                        leave(PLAYER)
                        return@on
                    }
                    Players.None -> {}
                }
            }
            if (totems.value && Inventory.count(Items.TOTEM_OF_UNDYING) <= minTotems.value) leave(TOTEMS)
            if (player.health < minHp.value && hp.value) leave(HP)
        }
    }

    /** Off with the reason in chat (with [disableSetting]), the bad slot packet, then the disconnect. */
    private fun leave(reason: String) {
        if (disableSetting.value) {
            disable()
            moduleChat(this, reason)
        }
        if (instantLeave.value) Packets.send(ServerboundSetCarriedItemPacket(-1337))
        mc.connection?.connection?.disconnect(Component.nullToEmpty("[Horizon] $reason"))
    }

    private companion object {
        // The English lines for "modules.misc.auto-leave.*".
        const val PLAYER = "Logged out because there was a player"
        const val TOTEMS = "Logged out because out of totems"
        const val HP = "Logged out because ur hp is low"
    }
}
