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
 * - `server_rule`: the chat box's length ([maxChatLength]), brightness ([gamma]), the chat staying open in a portal
 *   ([antiPortalTraps]), F3+A ([noChunkReloading]), shaders ([noShaders]), hits on end crystals ([crystalOptimizer],
 *   [crystalHitDamages]) and the warnings before leaving a competitive game ([competitiveGame], [competitiveCommand]).
 *   `override-nametag-render-distance` isn't here: Lunar's own 26.x build changes a constant 26.3 no longer reads.
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
        if (rule("override-max-chat-length", false)) int(SERVER_RULE, "max-chat-length", 256).coerceIn(1, 256) else null

    /** A server rule's switch, while the module is on. */
    fun rule(key: String, default: Boolean) = on(SERVER_RULE) && bool(SERVER_RULE, key, default)

    /** Server rules' gamma when `override-brightness` is on: `brightness` (1..10000) / 100, else null. */
    fun gamma(): Float? = if (rule("override-brightness", false)) int(SERVER_RULE, "brightness", 50).coerceIn(1, 10000) / 100f else null

    /** Server rules' `anti-portal-traps` (on unless the server says otherwise): a portal doesn't close your chat. */
    fun antiPortalTraps() = rule("anti-portal-traps", true)

    /** Server rules' `disable-chunk-reloading`: F3+A does nothing. */
    fun noChunkReloading() = rule("disable-chunk-reloading", false)

    /** Server rules' `disable-shaders`: the shader pack is turned off. */
    fun noShaders() = rule("disable-shaders", false)

    /** Server rules' `crystal-optimizer`. */
    fun crystalOptimizer() = rule("crystal-optimizer", false)

    /** Server rules' `competitive-game`: leaving asks first. */
    fun competitiveGame() = rule("competitive-game", false)

    /** Server rules' `competitive-commands` (with their slash, as the server wrote them), while the module is on. */
    @Synchronized
    fun competitiveCommands(): List<String> {
        if (!on(SERVER_RULE)) return emptyList()
        val v = props["$SERVER_RULE.competitive-commands"] ?: return listOf("/server", "/servers", "/hub")
        if (v.kindCase != Value.KindCase.LIST_VALUE) return emptyList()
        return v.listValue.valuesList.filter { it.kindCase == Value.KindCase.STRING_VALUE }.map { it.stringValue }
    }

    /**
     * Whether sending [text] ("/" and the command) asks first, in a competitive game, as Lunar matches it: the command's
     * root (up to the first space, when that's past the third character, else all of it) in lower case is one of
     * [competitiveCommands] exactly.
     */
    fun competitiveCommand(text: String): Boolean {
        if (!competitiveGame()) return false
        val space = text.indexOf(' ')
        val root = if (space > 2) text.substring(0, space) else text
        return root.lowercase() in competitiveCommands()
    }

    companion object {
        /**
         * Crystal optimizer's test, as Lunar 26.x makes it: the hit hurts the crystal unless Weakness (its [weakness]
         * amplifier) outweighs it. With Weakness w = 4 x (amplifier + 1): it hurts when the attack damage attribute is over
         * w + 5, or when that attribute plus the held item's attack damage modifiers ([itemDamage]) plus Strength's
         * 3 x (amplifier + 1) is over w.
         */
        fun crystalHitDamages(weakness: Int?, strength: Int?, attackDamage: Double, itemDamage: Double): Boolean {
            if (weakness == null) return true
            val w = 4 * (weakness + 1)
            if (attackDamage > w + 5) return true
            val s = if (strength != null) 3 * (strength + 1) else 0
            return attackDamage + itemDamage + s - w > 0
        }

        const val PACKET_ENRICHMENT = "packet_enrichment"
        const val COMBAT = "combat"
        const val SERVER_RULE = "server_rule"
    }
}
