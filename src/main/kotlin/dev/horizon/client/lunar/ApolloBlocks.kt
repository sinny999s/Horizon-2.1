package dev.horizon.client.lunar

import com.google.protobuf.Value
import com.lunarclient.apollo.configurable.v1.ConfigurableSettings

/**
 * Not in the original: what an Apollo server's settings override ([com.lunarclient.apollo.configurable.v1.OverrideConfigurableSettingsMessage])
 * asks of Lunar's mods, to show you. Apollo sends Lunar's mod options under its `mod_setting` module, keyed
 * `<mod>.<option>` (`freelook.enabled` false turns Freelook off), or names a Lunar mod directly (`lunar_client_mod`,
 * off with `enable` false). Horizon has none of Lunar's mods, so nothing here changes what Horizon does. Game-free.
 */
object ApolloBlocks {
    /** One server's asks: Lunar mods it turns off, mods it lets on again, and other options it sets (`mod.option` -> value). */
    class Summary {
        val blocked = sortedSetOf<String>()
        val allowed = sortedSetOf<String>()
        val forced = sortedMapOf<String, String>()

        /** Apollo's own modules' settings (server rules and the like): `module.option` -> value, and modules turned off. */
        val server = sortedMapOf<String, String>()
        val isEmpty get() = blocked.isEmpty() && forced.isEmpty()

        fun add(settings: List<ConfigurableSettings>) {
            for (s in settings) {
                when {
                    s.hasLunarClientMod() -> {
                        val mod = s.lunarClientMod
                        if (!s.enable) block(mod) else allow(mod)
                        for ((k, v) in s.propertiesMap) option("$mod.$k", v)
                    }
                    s.hasApolloModule() && s.apolloModule == "mod_setting" -> for ((k, v) in s.propertiesMap) option(k, v)
                    s.hasApolloModule() -> {
                        val m = s.apolloModule
                        if (!s.enable) server[m] = "off" else server.remove(m)
                        for ((k, v) in s.propertiesMap) if (v.kindCase != Value.KindCase.NULL_VALUE) server["$m.$k"] = text(v)
                    }
                }
            }
        }

        private fun block(mod: String) {
            blocked += mod
            allowed -= mod
        }

        private fun allow(mod: String) {
            blocked -= mod
            allowed += mod
        }

        private fun option(key: String, v: Value) {
            val mod = key.substringBefore('.')
            val option = key.substringAfter('.', "")
            if (option == "enabled" && v.kindCase == Value.KindCase.BOOL_VALUE) {
                if (v.boolValue) allow(mod) else block(mod)
                return
            }
            if (v.kindCase == Value.KindCase.NULL_VALUE) {
                forced.remove(key)
                return
            }
            forced[key] = text(v)
        }
    }

    /** A protobuf Value as text. */
    fun text(v: Value): String = when (v.kindCase) {
        Value.KindCase.BOOL_VALUE -> if (v.boolValue) "on" else "off"
        Value.KindCase.NUMBER_VALUE -> v.numberValue.let { if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString() }
        Value.KindCase.STRING_VALUE -> v.stringValue
        Value.KindCase.LIST_VALUE -> v.listValue.valuesList.joinToString(", ", "[", "]") { text(it) }
        Value.KindCase.STRUCT_VALUE -> v.structValue.fieldsMap.entries.joinToString(", ", "{", "}") { "${it.key}: ${text(it.value)}" }
        else -> "none"
    }

    /** Lunar's mod ids as words ("player_hitbox" -> "Player Hitbox", "freelook" -> "Freelook"). */
    fun modName(id: String) = id.split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** "Freelook (invert pitch)" style for an option key. */
    fun optionName(key: String): String {
        val mod = modName(key.substringBefore('.'))
        val option = key.substringAfter('.', "").replace('-', ' ').replace('_', ' ')
        return if (option.isEmpty()) mod else "$mod: $option"
    }
}
