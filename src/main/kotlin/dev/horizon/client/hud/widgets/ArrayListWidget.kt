package dev.horizon.client.hud.widgets

import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import dev.horizon.client.setting.EnumSetting
import java.util.Locale
import kotlin.math.max

/**
 * A row per enabled module (not the ClickGUI, nor a module hidden from it: [Module.listHidden], by default everything
 * visual and what only runs in the background; the eye in the ClickGUI's module header), built like the other list
 * widgets but with no header: a
 * plate with the name and, with [modes], another with the module's mode; rows slide in and out over 250 ms, and on the
 * right half of the screen it's right-aligned. Rows go widest first (or by name) and glide to their new places when
 * the order changes. Empty, it shows a faint "Array List" plate in chat or the HUD editor, to be grabbed.
 */
object ArrayListWidget : HudWidget("array-list-widget") {
    private val modes = bool("modes", true)
    private val byWidth = bool("sort-by-width", true)

    private class Row(val module: Module) : ListRow() {
        /** The row's y in the list, chasing its place in the order (below 0 until first placed). */
        val y = Animation(Easing.OUT_QUINT, 250).also { it.value = -1f }
        val modeSetting = module.settings.firstOrNull { it.id == "mode" } as? EnumSetting<*>
        /** The mode value [modeText] was written for. */
        var modeValue: Any? = null
        var modeText = ""
        // This frame's label, mode and width (worked out once, not again for every comparison of the sort).
        var label = ""
        var mode = ""
        var width = 0f
    }

    private val rows = ArrayList<Row>()

    /** The module's mode as the ClickGUI writes it ("AROUND_PLAYERS" -> "Around Players"), or "". */
    private fun mode(r: Row): String {
        if (!modes.value) return ""
        val v = r.modeSetting?.value ?: return ""
        if (v !== r.modeValue) {
            r.modeValue = v
            r.modeText = v.name.lowercase(Locale.ROOT).split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
        return r.modeText
    }

    private fun measure(r: Row) {
        r.label = r.module.name.trim()
        r.mode = mode(r)
        // The new look's plates, the text 4 in from each side
        r.width = cellWidth(r.label) + if (r.mode.isEmpty()) 0f else 1f + cellWidth(r.mode)
    }

    override fun render() {
        for (m in Modules.all) {
            if (m is ClickGuiModule) continue
            val row = rows.firstOrNull { it.module === m }
            if (m.enabled && !m.listHidden) {
                if (row == null) rows += Row(m) else row.removing = false
            } else {
                row?.removing = true
            }
        }
        rows.removeIf { it.step() }
        val alignRight = left - screenW / 2f > 0
        if (rows.isEmpty()) {
            if (editorMode) cell(PLACEHOLDER, left, top, alpha = 0.5f)
            size(cellWidth(PLACEHOLDER), 13f)
            return
        }
        var width = 0f
        for (r in rows) {
            measure(r)
            width = max(width, r.width)
        }
        val ordered = if (byWidth.value) rows.sortedByDescending { it.width } else rows.sortedBy { it.module.name }
        var y = 0f
        for (r in ordered) {
            val t = r.anim.value
            if (r.y.value < 0f) r.y.value = y
            r.y.update(y)
            val ry = top + r.y.value
            val label = r.label
            val md = r.mode
            val rx = if (alignRight) left + width - r.width + (1 - t) * 10 else left - (1 - t) * 10
            val cw = cell(label, rx, ry, alpha = t)
            if (md.isNotEmpty()) cell(md, rx + cw + 1f, ry, Tok.T3, t)
            y += 14 * t
        }
        size(width, max(y - 1f, 13f))
    }

    private const val PLACEHOLDER = "Array List"
}
