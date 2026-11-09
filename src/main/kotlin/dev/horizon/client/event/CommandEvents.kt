package dev.horizon.client.event

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.client.multiplayer.ClientSuggestionProvider

/** `ClientPacketListener.sendChat` HEAD: a chat line about to go out; cancelling drops it. */
class SendMessageEvent(val message: String) : Event()

/**
 * Asked by the chat box's suggestions (`CommandSuggestions.updateCommandInfo`); a handler
 * that cancels it hands over its own [dispatcher], [source] and [prefix], and input starting with [prefix] is completed
 * against them. One shared instance.
 */
object InputSuggestorEvent : Event() {
    var source: ClientSuggestionProvider? = null
    var prefix = ""
    var dispatcher: CommandDispatcher<ClientSuggestionProvider>? = null
}
