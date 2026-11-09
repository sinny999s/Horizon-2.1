package dev.horizon.client.module.player

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.config.Friends
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

/**
 * A system message mentioning "tpaccept" is answered with `/tpaccept` (naming the
 * sender with Grief on). At most one message per 250 ms is looked at.
 */
class AutoTpaAccept : Module("auto-tpa-accept", Categories.PLAYER) {
    val grief = bool("grief", false)
    val onlyFriends = bool("only-friends", true)

    private val timer = MsTimer()

    init {
        // On the network thread.
        on<ReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundSystemChatPacket ?: return@on
            if (!timer.passed(250)) return@on
            val text = p.content().string
            if ("телепортироваться" in text || "tpaccept" in text) {
                if (!onlyFriends.value || Friends.isFriend(sender(text))) {
                    mc.connection?.sendCommand("tpaccept " + if (grief.value) sender(text) else "")
                }
            }
            timer.reset()
        }
    }

    /** The first listed player whose name the message contains, else "-". */
    private fun sender(text: String): String =
        mc.connection?.listedOnlinePlayers?.firstOrNull { it.profile.name in text }?.profile?.name ?: "-"
}
