package dev.horizon.client.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import net.minecraft.network.chat.Component

/** A client chat command, typed after the prefix ("^name …") and built on Brigadier. */
abstract class ClientCommand(val name: String) {
    protected val mc: Minecraft get() = Minecraft.getInstance()

    /** The arguments and executors under the command's literal. */
    abstract fun build(builder: LiteralArgumentBuilder<ClientSuggestionProvider>)

    fun register(dispatcher: CommandDispatcher<ClientSuggestionProvider>) {
        val builder = literal(name)
        build(builder)
        dispatcher.register(builder)
    }

    protected fun <T> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<ClientSuggestionProvider, T> =
        RequiredArgumentBuilder.argument(name, type)

    protected fun literal(name: String): LiteralArgumentBuilder<ClientSuggestionProvider> = LiteralArgumentBuilder.literal(name)

    /** A plain line (legacy colour codes in it apply). */
    fun message(text: String) = message(Component.nullToEmpty(text))

    /** "> " (green, gray), then [c], on the game thread. */
    fun message(c: Component) {
        val line = Component.empty().append(Component.literal(">").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
        line.append(c)
        if (mc.isSameThread) mc.player?.sendSystemMessage(line) else mc.executeIfPossible { mc.player?.sendSystemMessage(line) }
    }
}
