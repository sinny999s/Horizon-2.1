package dev.horizon.client

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Client chat output: a green ">", then the message (legacy § colour codes allowed). */
object Chat {
    fun send(text: String) {
        val msg = Component.empty()
            .append(Component.literal(">").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
            .append(Component.nullToEmpty(text))
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) mc.player?.sendSystemMessage(msg) else mc.execute { mc.player?.sendSystemMessage(msg) }
    }
}
