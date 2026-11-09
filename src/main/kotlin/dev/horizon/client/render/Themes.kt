package dev.horizon.client.render

import dev.horizon.client.config.Storage
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.extension

/**
 * A theme as saved in `horizon/themes/<id>.hzn` (colours as ARGB ints). Mutable: the theme editor
 * writes into it, which previews live when it is the current theme. Equality is over all fields.
 */
@Serializable
data class ThemeData(
    val id: String,
    var primaryColor: Int,
    var secondaryColor: Int,
    var blurColor: Int,
    var widgetBlurColor: Int,
    var outlineColor: Int,
    var textColor: Int,
    var nonactiveTextColor: Int,
    var slidersColor: Int,
    var nonActiveModuleColor: Int,
    var moduleColor: Int,
    var moduleOutlineColor: Int,
    var nonActiveOutlineModuleColor: Int,
) {
    /** The 12 colours in the editor's order, by id. */
    fun colors(): LinkedHashMap<String, Int> = linkedMapOf(
        "primary" to primaryColor, "secondary" to secondaryColor, "blur" to blurColor, "widget-blur" to widgetBlurColor,
        "outline" to outlineColor, "text" to textColor, "inactive-text" to nonactiveTextColor, "sliders" to slidersColor,
        "inactive-module" to nonActiveModuleColor, "module" to moduleColor, "module-outline" to moduleOutlineColor,
        "inactive-module-outline" to nonActiveOutlineModuleColor,
    )

    fun set(colorId: String, c: Int) {
        when (colorId) {
            "primary" -> primaryColor = c
            "secondary" -> secondaryColor = c
            "blur" -> blurColor = c
            "widget-blur" -> widgetBlurColor = c
            "outline" -> outlineColor = c
            "text" -> textColor = c
            "inactive-text" -> nonactiveTextColor = c
            "sliders" -> slidersColor = c
            "inactive-module" -> nonActiveModuleColor = c
            "module" -> moduleColor = c
            "module-outline" -> moduleOutlineColor = c
            "inactive-module-outline" -> nonActiveOutlineModuleColor = c
        }
    }

    companion object {
        /** Horizon's built-in theme: crimson red and ruby. A saved theme with the same id replaces it. */
        fun default() = preset("Horizon", 0xFFFF2A4A.toInt(), 0xFF99142E.toInt())

        /**
         * The redesign mockup's themes (design/clickgui's THEMES) besides the user's own: its two
         * accents, the rest of the colours as the old default's with its tints taken from [secondary] (as the default
         * tints its greys towards its purple).
         */
        fun presets() = listOf(
            preset("Horizon", 0xFFFF2A4A.toInt(), 0xFF99142E.toInt()),
            preset("Classic Cyan", 0xFF38F4E0.toInt(), 0xFF9547EF.toInt()),
            preset("Rose", 0xFFFF6FAE.toInt(), 0xFFB78CFF.toInt()),
            preset("Amber", 0xFFFF6A3D.toInt(), 0xFFFFB547.toInt()),
            preset("Aurora", 0xFF3DF5A0.toInt(), 0xFF3DA0FF.toInt()),
            preset("Frost", 0xFFE9F0FF.toInt(), 0xFF8FA2C0.toInt()),
            monochrome(),
        )

        /** Black through white only, matching the title screen and the menus: light grey into mid grey. */
        fun monochrome() = preset("Monochrome", 0xFFBEBEC4.toInt(), 0xFF5E5E66.toInt())

        private fun preset(id: String, primary: Int, secondary: Int): ThemeData {
            fun tint(grey: Int, t: Float) = Colors.lerp(0xFF000000.toInt() or (grey * 0x010101), secondary, t)
            val module = tint(0x7F, 0.45f) and 0xFFFFFF
            return ThemeData(
                id, primary, secondary, -9868951, tint(0x91, 0.2f), tint(0x96, 0.35f), -1, -8355712, primary, 1008606760,
                0x3C000000 or module, 0xC7000000.toInt() or module, tint(0x38, 0.25f),
            )
        }
    }
}

/**
 * The theme list and the current theme. Loaded from `horizon/themes/<id>.hzn`; when none has the
 * saved id, the built-in default is added. Saving writes every listed theme; removing one only drops it from the list
 * (its file stays).
 */
object Themes {
    val list = ArrayList<ThemeData>()

    var current: ThemeData = ThemeData.default()

    private val dir get() = Storage.dir("themes")

    fun load() {
        list.clear()
        try {
            Files.list(dir).use { s ->
                s.filter { it.extension == "hzn" }.sorted().forEach { list += Storage.json.decodeFromString(ThemeData.serializer(), Files.readString(it)) }
            }
        } catch (e: Exception) {
            // Every theme is dropped when one file doesn't parse (on purpose).
            dev.horizon.client.HorizonClient.LOG.warn("Themes are not loaded from file for some reasons.", e)
            list.clear()
        }
        // Monochrome came later, so it's added once on its own for lists that already had the rest
        // (before the current theme is looked up, so it can be the one picked).
        if (Storage.shared.themePresets && !Storage.shared.themeMonochrome) {
            if (list.none { it.id == "Monochrome" }) list += ThemeData.monochrome()
            Storage.shared.themeMonochrome = true
        }
        current = list.firstOrNull { it.id == Storage.shared.themeConfig } ?: ThemeData.default().also { list += it }
        // The redesign's themes, added once (one removed later stays removed).
        if (!Storage.shared.themePresets) {
            for (p in ThemeData.presets()) if (list.none { it.id == p.id }) list += p
            Storage.shared.themePresets = true
            Storage.shared.themeMonochrome = true
            save()
        }
    }

    fun save() {
        for (t in list) Storage.write(dir.resolve("${t.id}.hzn"), ThemeData.serializer(), t)
    }
}
