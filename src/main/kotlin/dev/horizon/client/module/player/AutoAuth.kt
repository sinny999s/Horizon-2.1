package dev.horizon.client.module.player

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.ChatFormatting
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Locale

/**
 * Answers a server's register / login prompt in system chat with the password. With
 * an empty password every system message reports the error instead (on purpose).
 */
class AutoAuth : Module("auto-auth", Categories.PLAYER) {
    val password = string("password", "c1a2t3l4e5a6n")

    init {
        // On the network thread.
        on<ReceivePacketEvent> { e ->
            val p = e.packet as? ClientboundSystemChatPacket ?: return@on
            val text = p.content().string.lowercase(Locale.ROOT)
            val pass = password.value
            if (pass.isEmpty()) {
                moduleMessage(name.trim(), ChatFormatting.RED.toString() + "Registration error: Password is empty!")
                Notifications.post("Auto Auth", "Registration error: Password is empty!", 4f, NotificationType.ERROR)
                return@on
            }
            if ("/reg" in text || "/register" in text || "зарегистрируйтесь" in text) {
                mc.connection?.sendCommand("reg $pass $pass")
                Notifications.post("Auto Auth", "Registration completed!", 4f, NotificationType.SUCCESS)
                return@on
            }
            if ("авторизуйтесь" !in text && "/l " !in text && "/login" !in text) return@on
            mc.connection?.sendCommand("login $pass")
            Notifications.post("Auto Auth", "Logged in!", 4f, NotificationType.SUCCESS)
        }
    }
}
