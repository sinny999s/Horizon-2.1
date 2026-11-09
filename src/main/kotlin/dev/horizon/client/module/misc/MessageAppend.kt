package dev.horizon.client.module.misc

import dev.horizon.client.command.Commands
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.world.item.Items
import kotlin.random.Random

/**
 * An outgoing chat message is cancelled and sent again as prefix + message + postfix
 * (+ a random emoticon). Commands, the client prefix "^", short messages with 3+ digits and messages while holding a map
 * are left alone; a leading "!" stays in front.
 */
class MessageAppend : Module("message-append", Categories.MISC) {
    val prefix = string("prefix", "> ")
    val postfix = string("postfix", " [Horizon]")
    val emoji = bool("emoji", true)

    val emojis = arrayOf(
        "^_^", "^-^", "^o^", "^O^", "o_o", "O_O", "0_0", "x_x", ">_<", "-_-", ";_;", "T_T", "=_=", "v_v", "xD", "o.O",
        "O.o", "._.", "o_o;", "O_O;", ">:(", ":)", ":D", ":P", ":o", ":O", ":3", ":|", ":/", ":*", ";)", ">:D", ">:3c",
        "XD", "xP", "^_^", "(^-^)", "(>_<)", "(T_T)", "(U3O)", "o_o", "O_O", "0_0", "(v_v)", "(x_x)", "(^o^)", "(^O^)",
        "(;_;)", "(=_=)", "(^3^)", "(^w^)", "UwU", "OwO", ">w<", "xD", ";D", "(>o<)", "(._.)", "('~')", "(*_*)", "(¬_¬)",
        "(•_•)", "(°_°)", "(>_<)b",
    )

    /** The last message this module sent, so its own resend passes through. */
    private var last: String? = null

    init {
        on<SendPacketEvent> { e ->
            val player = mc.player ?: return@on
            if (mc.level == null) return@on
            val packet = e.packet as? ServerboundChatPacket ?: return@on
            var message = packet.message()
            if (message == last) return@on
            if (player.mainHandItem.item == Items.FILLED_MAP || player.offhandItem.item == Items.FILLED_MAP) return@on
            if (message.length < 7 && message.count { it.isDigit() } >= 3) return@on
            if (message.startsWith("/") || message.startsWith(Commands.prefix)) return@on
            var bang = false
            if (message.startsWith("!")) {
                message = message.substring(1)
                bang = true
            }
            val sent = (if (bang) "!" else "") + prefix.value + message + postfix.value + (if (emoji.value) " " + emojis.random(Random.Default) else "")
            last = sent
            player.connection.sendChat(sent)
            e.cancel()
        }
    }
}
