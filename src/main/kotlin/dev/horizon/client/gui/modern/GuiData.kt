package dev.horizon.client.gui.modern

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Module
import dev.horizon.client.setting.ActionSetting
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BlocksSetting
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EntitiesSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.GroupSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.ItemsSetting
import dev.horizon.client.setting.ListSetting
import dev.horizon.client.setting.NotationSetting
import dev.horizon.client.setting.Setting
import dev.horizon.client.setting.StringSetting
import dev.horizon.client.setting.ValueLabels
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Locale

/** What the new ClickGUI reads about modules and settings beyond what they hold. */
internal object GuiData {
    /** One line per module (assets/horizon/gui/descriptions.txt, written with the mockup). */
    private val descriptions: Map<String, String> by lazy {
        try {
            GuiData::class.java.getResourceAsStream("/assets/${HorizonClient.ID}/gui/descriptions.txt")!!.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        } catch (t: Throwable) {
            HorizonClient.LOG.warn("No module descriptions", t)
            emptyMap()
        }
    }

    fun description(m: Module) = descriptions[m.id] ?: ""

    // ---- favourites: horizon/gui/favourites.json ----

    private val favFile get() = Storage.dir("gui").resolve("favourites.json")
    private val favs: MutableSet<String> by lazy { Storage.read(favFile, ListSerializer(String.serializer()))?.toMutableSet() ?: mutableSetOf() }

    fun isFav(m: Module) = m.id in favs

    fun toggleFav(m: Module) {
        if (!favs.remove(m.id)) favs += m.id
        Storage.write(favFile, ListSerializer(String.serializer()), favs.toList())
    }

    // ---- names and values ----

    fun name(m: Module) = m.name.trim()
    fun name(s: Setting<*>) = s.displayName.trim()

    /** "AROUND_PLAYERS" or "copy-from-player" -> "Around Players", "Copy From Player". */
    fun words(id: String) = id.lowercase(Locale.ROOT).split('_', '-').filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    /** An enum constant as the GUI writes it. */
    fun enumName(e: Enum<*>) = words(e.name)

    /** The module's mode (its "mode" enum) as the ArrayList shows it, or "". */
    fun mode(m: Module): String = (m.settings.firstOrNull { it.id == "mode" } as? EnumSetting<*>)?.let { enumName(it.value) } ?: ""

    /** A number setting's value as the sliders show it, or its word from ValueLabels (Aura's fov 1 = Trigger). */
    fun number(s: Setting<*>, v: Number): String {
        val shown = if (s is IntSetting) v.toInt().toString() else v.toFloat().toString()
        val label = ValueLabels.get(s.id, v, shown)
        return if (label == shown) shown else words(label)
    }

    /** Settings that count towards "changed from default" (the rest hold no value). */
    fun valued(s: Setting<*>) = s !is GroupSetting && s !is ActionSetting && s !is NotationSetting

    /** The default's saved form, kept per setting (list settings are edited by copy, so their default stays put). */
    private val defaults = HashMap<Setting<*>, String>()

    @Suppress("UNCHECKED_CAST")
    private fun defaultSave(s: Setting<*>): String = defaults.getOrPut(s) {
        val v = (s as Setting<Any>).value
        s.value = s.default
        val saved = s.save()
        s.value = v
        saved
    }

    fun changed(s: Setting<*>) = valued(s) && s.id != "enabled" && s.save() != defaultSave(s)

    fun changedCount(m: Module) = m.settings.count { changed(it) && it.id != "bind" && it.id != "hidden" }

    /** The default, as the footer names it. */
    fun defaultText(s: Setting<*>): String = when (s) {
        is BoolSetting -> if (s.default) "on" else "off"
        is EnumSetting<*> -> enumName(s.default)
        is IntSetting -> number(s, s.default)
        is FloatSetting -> number(s, s.default)
        is ColorSetting -> hex(s.default.rgb)
        is BindSetting -> Keys.name(s.default)
        is StringSetting -> if (s.default.isEmpty()) "empty" else "\"${s.default}\""
        is ListSetting<*> -> defaultSave(s).count { it == ',' }.let { if (defaultSave(s) == "[]") "none" else "${it + 1} entries" }
        else -> ""
    }

    fun hex(argb: Int): String {
        val a = argb ushr 24
        val rgb = String.format("#%06X", argb and 0xFFFFFF)
        return if (a < 255) rgb + String.format("%02X", a) else rgb
    }

    fun isList(s: Setting<*>) = s is BlocksSetting || s is ItemsSetting || s is EntitiesSetting
}
