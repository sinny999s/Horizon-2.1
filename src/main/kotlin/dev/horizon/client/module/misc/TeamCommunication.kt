package dev.horizon.client.module.misc

import dev.horizon.client.command.Commands
import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.combat.teamChatBypass
import dev.horizon.client.config.Friends
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.Bind
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every chat message goes out XOR-encrypted with [key] as
 * "!cat_<base64>_lean"; such messages from others are decrypted into chat (and hidden when a friend sent them).
 * [sendCoords] shares our x/z (a teammate's go to the compass), [tpHere] makes teammates /tpa (or /marry tp) to us.
 */
class TeamCommunication : Module("team-communication", Categories.MISC) {
    val key = string("key", "horizon")
    val sendCoords = bind("send-coords", Bind())
    val tpHere = bind("tp-here", Bind())
    val marryTp = bool("marry-tp", false)

    /** Set while our own encrypted message is being sent, so it isn't encrypted again. */
    val sending = AtomicBoolean(false)

    init {
        on<SendPacketEvent> { e ->
            if (mc.player == null) return@on
            val p = e.packet as? ServerboundChatPacket ?: return@on
            if (sending.get() || teamChatBypass.get()) return@on
            sending.set(true)
            mc.connection!!.sendChat(encrypt(p.message()))
            sending.set(false)
            e.cancel()
        }
        on<InputEvent> { e ->
            if (e.action != InputEvent.Action.Press) return@on
            if (pressed(tpHere.value, e)) mc.connection!!.sendChat(if (marryTp.value) "action_marry_tp" else "action_tp")
            if (pressed(sendCoords.value, e)) {
                val p = mc.player!!
                mc.connection!!.sendChat("xCoord" + p.x.toInt() + "zCoord" + p.z.toInt() + "end")
            }
        }
        on<ReceivePacketEvent> { e -> Misc2Util.received(e) { p -> received(p) } }
    }

    /** The key code and the device must both match, as for module binds. */
    private fun pressed(b: Bind, e: InputEvent) = b.key != -1 && b.key == e.key && b.mouse == (e.device == InputEvent.Device.Mouse)

    /** Whether to hide the message: a decrypted one from a friend. */
    private fun received(packet: Packet<*>): Boolean {
        if (packet !is ClientboundSystemChatPacket) return false
        val s = packet.content().string
        if (!s.contains("cat_") || !s.contains("_lean")) return false
        val text = decrypt(s.substringAfter("cat_").substringBefore("_lean"))
        val from = sender(s)
        if (from == mc.player!!.gameProfile.name) return false
        if (text.contains("xCoord")) {
            try {
                val x = Integer.parseInt(text.substringAfter("xCoord").substringBefore("zCoord"))
                val z = Integer.parseInt(text.substringAfter("zCoord").substringBefore("end"))
                Commands.gps = BlockPos(x, 0, z)
            } catch (_: Exception) {
            }
            return false
        }
        if (text.contains("action_marry_tp")) {
            mc.connection!!.sendCommand("marry tp $from")
            return false
        }
        if (text.contains("action_tp")) {
            mc.connection!!.sendCommand("tpa $from")
            return false
        }
        Misc2Util.chat(this, "$from: $text")
        return Friends.isFriend(from)
    }

    fun encrypt(input: String): String = "!cat_" + encode(xor(input.toByteArray(Charsets.UTF_8))) + "_lean"

    fun decrypt(input: String): String = String(xor(decode(input)), Charsets.UTF_8)

    private fun xor(bytes: ByteArray): ByteArray {
        val k = key.value.toByteArray(Charsets.UTF_8)
        return ByteArray(bytes.size) { i -> (bytes[i].toInt() xor k[i % k.size].toInt()).toByte() }
    }

    /** Base64 with the characters chat filters dislike swapped: + → (, / → ), = → %. */
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes).replace('+', '(').replace('/', ')').replace('=', '%')

    private fun decode(s: String): ByteArray = Base64.getDecoder().decode(s.replace('(', '+').replace(')', '/').replace('%', '='))

    /** The last tab-list name the message contains. */
    private fun sender(message: String): String {
        var found = "FATAL ERROR"
        for (info in mc.connection!!.listedOnlinePlayers) if (message.contains(info.profile.name)) found = info.profile.name
        return found
    }
}
