package dev.horizon.client.lunar

import com.google.protobuf.Value
import com.lunarclient.apollo.configurable.v1.ConfigurableSettings

/**
 * What an Apollo server's settings turn on in Lunar Client itself, kept for the connection the way Lunar keeps them. On
 * joining, Apollo sends every module's `enable` and options (keys are the option paths joined with `.`), and Lunar acts
 * on a module only while it's enabled. Read from Apollo 1.3.0's modules (github.com/LunarClient/Apollo) and Lunar Client's
 * own jars (2026-11-08). Game-free.
 * - `packet_enrichment` ([enrich]): which of Lunar's extra serverbound messages to send ([ApolloEnrichment]).
 * - `combat` ([noMissPenalty], [allowDigAndUse]): attacks the server lets Lunar make.
 * - `server_rule` ([maxChatLength]): the chat box's length.
 */
class ApolloRules {
    private val enabled = HashMap<String, Boolean>()
    private val props = HashMap<String, Value>()

    @Synchronized
    fun add(settings: List<ConfigurableSettings>) {
        for (s in settings) {
            if (!s.hasApolloModule()) continue
            val m = s.apolloModule
            enabled[m] = s.enable
            for ((k, v) in s.propertiesMap) {
                if (v.kindCase == Value.KindCase.NULL_VALUE) props.remove("$m.$k") else props["$m.$k"] = v
            }
        }
    }

    @Synchronized
    fun on(module: String) = enabled[module] == true

    @Synchronized
    fun bool(module: String, key: String, default: Boolean): Boolean =
        props["$module.$key"]?.takeIf { it.kindCase == Value.KindCase.BOOL_VALUE }?.boolValue ?: default

    @Synchronized
    fun int(module: String, key: String, default: Int): Int =
        props["$module.$key"]?.takeIf { it.kindCase == Value.KindCase.NUMBER_VALUE }?.numberValue?.toInt() ?: default

    /**
     * Whether Lunar sends packet enrichment's [message] ("player-attack", "player-chat-open", ...): the module is on and
     * so is the message's `<message>.send-packet`. Apollo before 1.2.2 had only `enable`, meaning all of them.
     */
    fun enrich(message: String) = on(PACKET_ENRICHMENT) && bool(PACKET_ENRICHMENT, "$message.send-packet", true)

    /** Combat's `disable-block-miss-penalty` (the crosshair on a block) or `disable-miss-penalty` (anything else). */
    fun noMissPenalty(onBlock: Boolean) =
        on(COMBAT) && bool(COMBAT, if (onBlock) "disable-block-miss-penalty" else "disable-miss-penalty", false)

    /** Combat's `allow-dig-and-use`: attacking while using an item, and using one while digging. */
    fun allowDigAndUse() = on(COMBAT) && bool(COMBAT, "allow-dig-and-use", false)

    /** Server rules' `max-chat-length` when `override-max-chat-length` is on (1..256), else null. */
    fun maxChatLength(): Int? =
        if (on(SERVER_RULE) && bool(SERVER_RULE, "override-max-chat-length", false)) int(SERVER_RULE, "max-chat-length", 256).coerceIn(1, 256) else null

    companion object {
        const val PACKET_ENRICHMENT = "packet_enrichment"
        const val COMBAT = "combat"
        const val SERVER_RULE = "server_rule"
    }
}
