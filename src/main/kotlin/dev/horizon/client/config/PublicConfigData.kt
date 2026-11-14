package dev.horizon.client.config

import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.ListSetting
import dev.horizon.client.setting.Setting
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What a public config ([PublicConfigs]) may hold, and how one becomes a config of your own. Game-free.
 *
 * A public config carries only settings: switches, numbers (kept in their ranges), choices, colours and block/item
 * lists. Nothing personal goes up or comes down: no text settings (chat messages, commands, webhooks, passwords,
 * coordinates, names), no keybinds, no Array List choices, and nothing of the modules that send messages or hold
 * accounts and keys ([PERSONAL]). The same cleaning runs before an upload and after a download, so a config sent to
 * the service by other means can't plant any of that either.
 */
object PublicConfigData {
    /** Modules a public config never touches: they send chat or commands of their own, or hold your accounts and keys. */
    val PERSONAL = setOf(
        "spammer", "announce", "auto-ez", "message-append", "auto-leave", "auto-respawn", "discord-alerts", "auto-auth",
        "team-communication", "name-protect", "auto-sign",
    )

    /** Settings of every module that stay yours: keybinds and whether it shows in the Array List. */
    private val PERSONAL_SETTINGS = setOf("bind", "hidden")

    private const val MAX_LIST = 2048
    private const val MAX_ENTRY = 128
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())

    /** The value [s] may carry in a public config, cleaned (a number brought into range), or null when it can't. */
    fun shareable(s: Setting<*>, value: String): String? = when (s) {
        is BoolSetting -> value.takeIf { it == "true" || it == "false" }
        is IntSetting -> value.trim().toIntOrNull()?.coerceIn(s.range)?.toString()
        is FloatSetting -> value.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(s.range)?.toString()
        is EnumSetting<*> -> value.takeIf { v -> s.constants.any { it.name == v } }
        is ColorSetting -> value.trim().toIntOrNull()?.toString()
        is ListSetting<*> -> value.takeIf { list(it) }
        // Text, keybinds, buttons, groups and anything else.
        else -> null
    }

    private fun list(value: String): Boolean = try {
        val ids = json.decodeFromString(strings, value)
        ids.size <= MAX_LIST && ids.all { it.length <= MAX_ENTRY }
    } catch (e: Exception) {
        false
    }

    /** [entries] cut down to what a public config may hold, for the modules of [holders] (others are dropped). */
    fun clean(entries: List<HolderEntry>, holders: List<SettingsHolder>): List<HolderEntry> {
        val byId = holders.associateBy { it.id }
        val out = ArrayList<HolderEntry>()
        for (e in entries) {
            val h = byId[e.id] ?: continue
            if (h.id in PERSONAL) continue
            val settings = h.settings.associateBy { it.id }
            val seen = HashSet<String>()
            val list = e.settings.mapNotNull { se ->
                if (se.id in PERSONAL_SETTINGS || !seen.add(se.id)) return@mapNotNull null
                val s = settings[se.id] ?: return@mapNotNull null
                shareable(s, se.value)?.let { SettingEntry(se.id, it) }
            }
            if (list.isNotEmpty()) out += HolderEntry(e.id, list)
        }
        return out
    }

    /** What publishing uploads: the live settings of [holders], cleaned. */
    fun live(holders: List<SettingsHolder>): List<HolderEntry> =
        clean(holders.map { h -> HolderEntry(h.id, h.settings.map { SettingEntry(it.id, it.save()) }) }, holders)

    /**
     * A config of your own from a public one's [entries]: its settings (cleaned), and your own live values for all it
     * doesn't set, the personal ones included.
     */
    fun adopt(entries: List<HolderEntry>, holders: List<SettingsHolder>): List<HolderEntry> {
        val got = clean(entries, holders).associate { it.id to it.settings.associate { s -> s.id to s.value } }
        return holders.map { h ->
            val theirs = got[h.id]
            HolderEntry(h.id, h.settings.map { s -> SettingEntry(s.id, theirs?.get(s.id) ?: s.save()) })
        }
    }

    /** How many of [holders]' settings [entries] would change from their live values. */
    fun changes(entries: List<HolderEntry>, holders: List<SettingsHolder>): Int {
        val live = holders.associate { h -> h.id to h.settings.associate { it.id to it.save() } }
        return clean(entries, holders).sumOf { e -> e.settings.count { live[e.id]?.get(it.id) != it.value } }
    }

    /**
     * The id a public config named [name] gets in your configs: its name in lower case, words joined by '-'. When
     * [taken] has it already (and it wasn't made from the same public config, [sameOrigin]), its public [id] is added.
     */
    fun localId(name: String, id: String, taken: Set<String>, sameOrigin: (String) -> Boolean): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trim('-').ifEmpty { "public" }
        if (base !in taken || sameOrigin(base)) return base
        return "$base-${id.lowercase()}"
    }
}
