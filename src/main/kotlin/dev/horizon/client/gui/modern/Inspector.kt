package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.setting.ActionSetting
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.FloatSetting
import dev.horizon.client.setting.GroupMode
import dev.horizon.client.setting.GroupSetting
import dev.horizon.client.setting.IntSetting
import dev.horizon.client.setting.ListSetting
import dev.horizon.client.setting.NotationSetting
import dev.horizon.client.setting.Setting
import dev.horizon.client.setting.StringSetting
import net.minecraft.client.Minecraft
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ClickGUI's right column: the selected module's header (name, switch, description, bind, hold, Array List eye,
 * favourite, reset) and its settings, one row each in declaration order. Rows slide open and shut as their visibility
 * changes; groups open and close; a middle click puts a setting back to its default. With no module selected it lists
 * the GUI's controls.
 */
internal object Inspector {
    /** The text's inset from the column's sides; rows (and their hover) reach 6 further out. */
    private const val PAD = 12f

    private var scroll = 0f
    private var scrollMax = 0f
    private var hover: Setting<*>? = null
    /** The module whose Array List eye the pointer is on, for the footer. */
    private var arrayListHover: Module? = null
    private var flash: Setting<*>? = null
    private var flashAt = 0L
    private var revealing: Setting<*>? = null

    /** Text fields: a string setting's, or a number's while its value is being typed. */
    private val fields = HashMap<Setting<*>, Ui.Field>()
    private var typing: Setting<*>? = null

    fun opened(m: Module) {
        scroll = 0f
        typing = null
    }

    /** Scrolls [s]'s row into view (opening its group) and flashes it. */
    fun reveal(s: Setting<*>) {
        s.parent?.let { it.opened = true }
        revealing = s
        flash = s
        flashAt = System.currentTimeMillis() + 250
    }

    fun commitField(f: Ui.Field) {
        val s = typing ?: return
        if (fields[s] !== f) return
        typing = null
        val v = f.text.replace(',', '.').toFloatOrNull() ?: return
        when (s) {
            is IntSetting -> s.value = v.roundToInt()
            is FloatSetting -> s.value = v
            else -> {}
        }
    }

    // ---- drawing ----

    fun draw(x: Float, y: Float, w: Float, h: Float) {
        Ui.box(x, y + 0.5f, 0.5f, h - 0.5f, 0f, Tok.LINE)
        val m = ModernGui.selected
        if (m == null) {
            empty(x, y, w, h)
            return
        }
        arrayListHover = null
        val headH = header(m, x, y, w)
        val footH = 16f
        val by = y + headH
        val bh = h - headH - footH
        hover = null
        body(m, x, by, w, bh)
        footer(m, x, y + h - footH, w, footH)
    }

    private val controls = listOf("LMB" to "Turn a module on or off", "RMB" to "Open its settings", "MMB" to "Bind it to a key",
        "Alt" to "Show every bind", "Ctrl F" to "Search (or just type)")

    /** Nothing selected: what the mouse and keys do here. */
    private fun empty(x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w / 2f
        var cy = y + h / 2f - 62f
        Ui.textC(Type.semi, "No module selected", cx, cy, Tok.T1)
        cy += 10f
        Ui.textC(Type.small, "Right click a module to see its settings here.", cx, cy, Tok.T3)
        cy += 16f
        Ui.box(x + PAD + 12f, cy - 6f, w - 2f * PAD - 24f, 0.5f, 0f, Tok.LINE)
        cy += 6f
        // keys in one column (their right sides in line), what they do in the next; the two centred together
        val keysW = controls.maxOf { Type.chip.width(it.first) } + 6f
        val split = cx - (keysW + 7f + controls.maxOf { Type.small.width(it.second) }) / 2f + keysW
        for ((key, what) in controls) {
            val kw = Type.chip.width(key) + 6f
            Ui.keycap(split - kw, cy - 4.5f, key)
            Ui.text(Type.small, what, split + 7f, cy, Tok.T2)
            cy += 14f
        }
    }

    private var descOf: Module? = null
    private var descLines: List<String> = emptyList()

    private fun header(m: Module, x: Float, y: Float, w: Float): Float {
        val px = x + PAD
        val pw = w - 2f * PAD
        if (descOf !== m) {
            descOf = m
            val desc = GuiData.description(m)
            descLines = if (desc.isNotEmpty()) Ui.wrap(Type.body, desc, pw, 3) else emptyList()
        }
        val lines = descLines
        val metaY = y + 32.5f + lines.size * 8.75f + (if (lines.isEmpty()) 0f else 5.5f)
        val headH = metaY + 14f + 8f - y
        val cat = Categories.ALL.first { m.subCategory in it.subCategories }.displayName.let { if (it == "Pvp") "PVP" else it.uppercase() }
        val sub = if (m.subCategory.id == "esp") "ESP" else m.subCategory.displayName.uppercase()
        var cy = y + 11.5f
        Ui.text(Type.crumb, cat, px, cy, Tok.T4)
        val cw = Type.crumb.width(cat)
        Ui.icon("chev", px + cw + 3f, cy - 2.75f, 5.5f, Tok.T4)
        Ui.text(Type.crumb, sub, px + cw + 11.5f, cy, Tok.T4)
        cy = y + 24f
        Ui.text(Type.h2, Ui.fit(Type.h2, GuiData.name(m), pw - 28f), px, cy, Tok.T1)
        Ui.switch("insp:" + m.id, px + pw - 22f, cy - 6.5f, m.enabled, big = true) { m.toggle() }
        for ((i, l) in lines.withIndex()) Ui.text(Type.body, l, px, y + 37f + i * 8.75f, Tok.T2)
        cy = metaY
        // the bind and whether it is held; then, quieter, the Array List eye, favourite, reset and close
        val b = m.bind
        val capturing = ModernGui.capture?.setting === m.bindSetting
        var bx = px
        bx += Ui.button("bind", bx, cy, 14f, if (capturing) "Press a key..." else if (b.key == -1) "No bind" else Keys.name(b), "key",
            active = capturing) { ModernGui.startCapture(m, m.bindSetting) } + 3f
        Ui.button("hold", bx, cy, 14f, if (b.hold) "Hold" else "Toggle", null) {
            m.bindSetting.value = m.bind.copy(hold = !b.hold)
            Sounds.SWITCH_RIGHT.play()
        }
        var ix = px + pw - 13f
        ghost("close", ix, cy, "x", 7f, Tok.T2) { ModernGui.select(null) }
        ix -= 15f
        ghost("reset", ix, cy, "reset", 7f, Tok.T2) {
            m.settings.forEach { if (it.id !in Module.META) it.reset() }
            Sounds.SETTINGS_CLOSE.play()
        }
        ix -= 15f
        val fav = GuiData.isFav(m)
        ghost("fav", ix, cy, if (fav) "favon" else "fav", 7f, if (fav) Tok.T1 else Tok.T2) { GuiData.toggleFav(m) }
        // Shown in the Array List or not (the footer says which while the pointer is on it).
        ix -= 15f
        val hid = m.listHidden
        if (ghost("hidden", ix, cy, if (hid) "eyeoff" else "eye", 7.5f, if (hid) Tok.T4 else Tok.T2) {
                m.listHidden = !hid
                Sounds.SWITCH_RIGHT.play()
            }) arrayListHover = m
        Ui.box(x + 0.5f, y + headH, w - 0.5f, 0.5f, 0f, Tok.LINE)
        return headH + 0.5f
    }

    /** An icon button with no box until the pointer is on it (14 square); returns whether it is. */
    private fun ghost(id: String, x: Float, y: Float, icon: String, size: Float, color: Int, click: () -> Unit): Boolean {
        val over = Ui.hit("btn:$id", x, y, 14f, 14f, { if (it == Keys.MOUSE_LEFT) click() })
        if (over) Ui.box(x, y, 14f, 14f, 4f, Tok.FILL2)
        Ui.icon(icon, x + (14f - size) / 2f, y + (14f - size) / 2f, size, if (over) Tok.T1 else color)
        return over
    }

    private fun footer(m: Module, x: Float, y: Float, w: Float, h: Float) {
        Ui.box(x + 0.5f, y, w - 0.5f, 0.5f, 0f, Tok.LINE)
        val cy = y + h / 2f
        val s = hover
        var tx = x + PAD
        fun part(text: String, strong: Boolean) {
            val font = if (strong) Type.statusB else Type.status
            Ui.text(font, text, tx, cy, if (strong) Tok.T2 else Tok.T3)
            tx += font.width(text) + 3f
        }
        val eye = arrayListHover
        if (eye != null) {
            part(if (eye.listHidden) "Hidden from the Array List" else "Shown in the Array List", true)
            part("· click to ${if (eye.listHidden) "show" else "hide"} it", false)
        } else if (s != null) {
            part(GuiData.name(s), true)
            if (GuiData.valued(s)) part("· default ${GuiData.defaultText(s)} · middle click resets", false)
        } else {
            val n = m.settings.count { GuiData.valued(it) && it.id !in Module.META }
            part("$n", true)
            part("settings ·", false)
            part("${GuiData.changedCount(m)}", true)
            part("changed", false)
        }
    }

    // ---- the settings ----

    /** A row to draw: the setting, its nesting, and its full height. */
    private class Row(val s: Setting<*>, val depth: Int, val height: Float, val visible: Boolean)

    private fun body(m: Module, x: Float, y: Float, w: Float, h: Float) {
        val left = x + PAD - 6f
        val width = w - 2f * PAD + 12f
        // the layout: top-level settings, a group's members under it while it is open
        val rows = ArrayList<Row>()
        val groups = HashMap<Any, GroupSetting>()
        for (s in m.settings) if (s is GroupSetting) groups[s.value] = s
        for (s in m.settings) {
            if (s.id in Module.META) continue
            val g = s.parent?.let { groups[it] }
            if (g != null) {
                if (g.value.mode == GroupMode.BOOLS) continue
                rows += Row(s, 1, rowHeight(s, width - INDENT), s.visible() && g.visible())
            } else {
                rows += Row(s, 0, rowHeight(s, width), s.visible())
            }
        }
        // total height, with the open/close and show/hide animations
        var total = 4f
        val shown = ArrayList<Pair<Row, Float>>()
        for (r in rows) {
            val g = r.s.parent?.let { groups[it] }
            val openF = if (g != null) Ui.anim("grp:" + g.id + "@" + m.id, if (g.value.opened) 1f else 0f, 380) else 1f
            val visF = Ui.anim("row:" + r.s.id + "@" + m.id, if (r.visible) 1f else 0f, 320)
            val f = openF * visF
            shown += r to f
            total += r.height * f
        }
        total += 8f
        scrollMax = max(0f, total - h)
        revealing?.let { target ->
            var ry = 4f
            for ((r, f) in shown) {
                if (r.s === target) break
                ry += r.height * f
            }
            scroll = (ry - h / 2f + 10f).coerceIn(0f, scrollMax)
            revealing = null
        }
        scroll = scroll.coerceIn(0f, scrollMax)
        val sy = Ui.anim("inspScroll", scroll, 300)
        Ui.hit("ibody", x, y, w, h, scroll = { a -> scroll = (scroll - a.toFloat() * 24f).coerceIn(0f, scrollMax); true }, pointer = false)
        Ui.pushClip(x, y, w, h)
        var ry = y + 4f - sy
        for ((r, f) in shown) {
            if (f < 0.01f) continue
            val rh = r.height * f
            if (ry + rh >= y - 2f && ry <= y + h + 2f) {
                val rx = if (r.depth == 1) left + INDENT else left
                val rw = if (r.depth == 1) width - INDENT else width
                if (r.depth == 1) Ui.box(left + 9.25f, ry, 0.5f, rh, 0f, Tok.LINE)
                val pa = Canvas.alpha
                Canvas.alpha = pa * f
                if (f < 0.999f) Ui.pushClip(rx - 2f, ry, rw + 4f, rh)
                row(m, r.s, rx, ry, rw, r.height)
                if (f < 0.999f) Ui.popClip()
                Canvas.alpha = pa
            }
            ry += rh
        }
        Ui.popClip()
        Ui.fade(ModernGui.backdrop, x + 1f, y, w - 2f, 8f, false, sy / 8f)
        Ui.fade(ModernGui.backdrop, x + 1f, y + h, w - 2f, 8f, true, (scrollMax - sy) / 8f)
        if (scrollMax > 0f) ModernGui.scrollbar(x + w - 4f, y + 2f, h - 4f, sy, scrollMax)
    }

    /** How far a group's members stand in from it. */
    private const val INDENT = 10f

    private fun rowHeight(s: Setting<*>, w: Float): Float = when (s) {
        is BoolSetting, is ColorSetting, is BindSetting -> 19f
        is IntSetting, is FloatSetting -> 30f
        is EnumSetting<*> -> if (segmented(s)) 37f else 19f
        is StringSetting -> 37f
        is ActionSetting -> 23f
        is NotationSetting -> 8f + Ui.wrap(Type.small, s.value.text, w - 28f, 8).size * 7.5f + 9f
        is GroupSetting -> if (s.value.mode == GroupMode.BOOLS) 19f + chipRows(s, w) * 15f + 2f else 20f
        is ListSetting<*> -> 19f + listRows(s, w) * 13f + 4f
        else -> 19f
    }

    private fun segmented(s: EnumSetting<*>) = s.constants.size <= 3 && s.constants.sumOf { it.name.length } <= 24

    private fun row(m: Module, s: Setting<*>, x: Float, y: Float, w: Float, h: Float) {
        val id = s.id + "@" + m.id
        val inside = Ui.mx >= x && Ui.mx <= x + w && Ui.my >= y && Ui.my <= y + h && ModernGui.popover?.contains(Ui.mx, Ui.my) != true
        if (inside) hover = s
        val flashing = flash === s && System.currentTimeMillis() - flashAt in -250..1400
        val fl = if (flashing) (1f - max(0L, System.currentTimeMillis() - flashAt) / 1400f) else 0f
        if (s !is GroupSetting && (inside || fl > 0f)) {
            Ui.box(x, y, w, h, 5f, Colors.lerp(0x08FFFFFF, 0x2EFFFFFF, fl))
        }
        // middle click on the row: back to the default
        if (GuiData.valued(s)) Ui.hit("row:$id", x, y, w, h, { b -> if (b == Keys.MOUSE_MIDDLE) reset(s) }, pointer = false)
        val lx = x + 6f
        val cy = y + 9.5f
        val right = x + w - 6f
        when (s) {
            is BoolSetting -> {
                label(s, lx, cy, right - 20f - lx)
                Ui.hit("bool:$id", x, y, w, h, { b -> if (b == Keys.MOUSE_LEFT) toggle(s) else if (b == Keys.MOUSE_MIDDLE) reset(s) })
                Ui.switch(id, right - 17f, cy - 5f, s.value)
            }
            is IntSetting, is FloatSetting -> slider(s, id, lx, y, right)
            is EnumSetting<*> -> enumRow(s, id, lx, y, right)
            is ColorSetting -> {
                label(s, lx, cy, right - 60f - lx)
                val c = s.value.rgb
                val hex = GuiData.hex(c)
                Ui.hit("color:$id", x, y, w, h, { b ->
                    if (b == Keys.MOUSE_LEFT) ModernGui.popover = ColorPicker(s, right - 118f, y + h + 2f, x, y, w, h)
                    else if (b == Keys.MOUSE_MIDDLE) reset(s)
                })
                checker(right - 13f, cy - 4.5f, 13f, 9f)
                Ui.box(right - 13f, cy - 4.5f, 13f, 9f, 2.5f, c, 0.5f, 0x2EFFFFFF)
                Ui.textR(Type.chip, hex, right - 17f, cy, Tok.T3)
            }
            is BindSetting -> {
                label(s, lx, cy, right - 60f - lx)
                val capturing = ModernGui.capture?.setting === s
                val name = if (capturing) "Press a key..." else if (s.value.key == -1) "None" else Keys.name(s.value)
                val bw = Type.small.width(name) + 19f
                Ui.button("bind:$id", right - bw, cy - 6.5f, 13f, name, "key", active = capturing) { ModernGui.startCapture(m, s) }
            }
            is StringSetting -> {
                label(s, lx, cy, right - lx)
                val f = fields.getOrPut(s) { Ui.Field(s.value, 256) { t -> s.value = t } }
                if (Ui.focused !== f) f.text = s.value
                val fy = y + 18f
                val focused = Ui.focused === f
                Ui.hit(Ui.fieldId(f), lx, fy, right - lx, 15f, { b -> if (b == Keys.MOUSE_LEFT) Ui.focused = f })
                Ui.box(lx, fy, right - lx, 15f, 4f, if (focused) Tok.FILL2 else Tok.FILL, 0.5f, if (focused) 0x52FFFFFF else Tok.LINE2)
                Ui.fieldText(f, lx + 5f, fy + 7.5f, right - lx - 10f, Type.label, "Empty")
            }
            is ActionSetting -> {
                val over = Ui.hit("act:$id", lx, y + 3.5f, right - lx, 16f, { b ->
                    if (b == Keys.MOUSE_LEFT) {
                        s.value.run()
                        Sounds.SWITCH_RIGHT.play()
                        // Actions that open a popup (Inventory Manager's kits) show it on its own screen.
                        if (dev.horizon.client.gui.popup.PopupScreen.popup != null) Minecraft.getInstance().gui.setScreen(dev.horizon.client.gui.popup.PopupScreen)
                    }
                })
                Ui.box(lx, y + 3.5f, right - lx, 16f, 4.5f, if (over) 0x1AFFFFFF else Tok.FILL2, 0.5f, if (over) 0x2EFFFFFF else Tok.LINE2)
                val name = GuiData.name(s)
                val tw = Type.semi.width(name)
                val mid = (lx + right) / 2f
                Ui.icon("play", mid - tw / 2f - 6f, y + 8.5f, 6f, if (over) Tok.T1 else Tok.T2)
                Ui.text(Type.semi, name, mid - tw / 2f + 3f, y + 11.5f, Tok.T1)
            }
            is NotationSetting -> {
                Ui.box(lx, y + 4f, right - lx, h - 8f, 5f, Tok.FILL, 0.5f, Tok.LINE)
                Ui.icon("info", lx + 5f, y + 8.5f, 7f, Tok.T3)
                var ly = y + 12f
                for (l in Ui.wrap(Type.small, s.value.text, w - 28f, 8)) {
                    Ui.text(Type.small, l, lx + 16f, ly, Tok.T2)
                    ly += 7.5f
                }
            }
            is GroupSetting -> group(m, s, id, x, y, w)
            is ListSetting<*> -> listRow(s, id, lx, y, right)
            else -> label(s, lx, cy, right - lx)
        }
    }

    /** The setting's name, and a dot after it while it is off its default. */
    private fun label(s: Setting<*>, x: Float, cy: Float, maxW: Float) {
        val name = Ui.fit(Type.label, GuiData.name(s), maxW - 6f)
        Ui.text(Type.label, name, x, cy, 0xDBF5F5F7.toInt())
        if (GuiData.changed(s)) Ui.box(x + Type.label.width(name) + 3.5f, cy - 1f, 2f, 2f, 1f, Tok.T3)
    }

    private fun checker(x: Float, y: Float, w: Float, h: Float) {
        Ui.box(x, y, w, h, 2.5f, 0xFF888888.toInt())
        var i = 0
        var cx = x
        while (cx < x + w) {
            var cyy = y
            var j = i
            while (cyy < y + h) {
                if (j % 2 == 0) Canvas.quad(cx, cyy, min(2f, x + w - cx), min(2f, y + h - cyy), 0xFF555555.toInt())
                cyy += 2f
                j++
            }
            cx += 2f
            i++
        }
    }

    private fun reset(s: Setting<*>) {
        s.reset()
        flash = s
        flashAt = System.currentTimeMillis()
        Sounds.SETTINGS_CLOSE.play()
    }

    private fun toggle(s: BoolSetting) {
        s.value = !s.value
        if (s.value) Sounds.ENABLE.play() else Sounds.DISABLE.play()
    }

    // ---- numbers: the split slider ----

    private fun slider(s: Setting<*>, id: String, lx: Float, y: Float, right: Float) {
        val min: Float
        val max: Float
        val value: Float
        val def: Float
        when (s) {
            is IntSetting -> { min = s.range.first.toFloat(); max = s.range.last.toFloat(); value = s.value.toFloat(); def = s.default.toFloat() }
            is FloatSetting -> { min = s.range.start; max = s.range.endInclusive; value = s.value; def = s.default }
            else -> return
        }
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val cy = y + 9.5f
        val valueText = GuiData.number(s, if (s is IntSetting) s.value else (s as FloatSetting).value)
        // the value: click to type one
        val f = fields.getOrPut(s) { Ui.Field("", 16) }
        val typingNow = typing === s && Ui.focused === f
        val vw = (if (typingNow) Type.value.width(f.text) + 6f else Type.value.width(valueText)) + 6f
        label(s, lx, cy, right - vw - lx - 6f)
        val vOver = Ui.hit(Ui.fieldId(f), right - vw + 3f, cy - 5f, vw, 10f, { b ->
            if (b == Keys.MOUSE_LEFT && typing !== s) {
                typing = s
                f.text = if (s is IntSetting) s.value.toString() else (s as FloatSetting).value.toString()
                Ui.focused = f
            } else if (b == Keys.MOUSE_MIDDLE) reset(s)
        })
        if (typingNow) {
            Ui.box(right - vw + 3f, cy - 5f, vw, 10f, 3f, 0x66000000, 0.5f, 0x66FFFFFF)
            Ui.fieldText(f, right - vw + 6f, cy, vw, Type.value, "")
        } else {
            if (vOver) Ui.box(right - vw + 3f, cy - 5f, vw, 10f, 3f, Tok.FILL2)
            Ui.textR(Type.value, valueText, right, cy, Tok.T1)
        }
        // the track, under the label from its left to the value's right
        val tx = lx
        val tw = right - lx
        val ty = y + 19f
        val frac = ((value - min) / span).coerceIn(0f, 1f)
        val anim = Ui.anim("sl:$id", frac, 220)
        val dragging = draggingSlider == s
        val over = Ui.hit("slider:$id", tx, ty - 4f, tw, 13f, { b -> if (b == Keys.MOUSE_MIDDLE) reset(s) },
            scroll = { a ->
                val step = if (s is IntSetting) 1f else 0.1f
                set(s, (value + (if (a > 0) step else -step)).coerceIn(min, max), min, max)
                true
            },
            drag = { mx, _ ->
                draggingSlider = s
                set(s, min + span * ((mx - tx) / tw).coerceIn(0f, 1f), min, max)
            },
            release = { draggingSlider = null })
        val hx = Ui.track(tx, ty, tw, if (dragging) frac else anim, ((def - min) / span).coerceIn(0f, 1f), over, dragging)
        if (dragging) {
            // the value over the handle while it moves
            val tipW = Type.tip.width(valueText) + 8f
            Ui.box(hx - tipW / 2f, ty - 16f, tipW, 11f, 3.5f, Tok.WHITE)
            for (k in 0 until 4) Ui.box(hx - 2f + k * 0.5f, ty - 5f + k * 0.5f, 4f - k, 0.5f, 0f, Tok.WHITE)
            Ui.textC(Type.tip, valueText, hx, ty - 10.5f, Tok.INK)
        }
    }

    private var draggingSlider: Setting<*>? = null

    private fun set(s: Setting<*>, v: Float, min: Float, max: Float) {
        when (s) {
            is IntSetting -> {
                val n = v.roundToInt()
                if (n != s.value) s.value = n
            }
            is FloatSetting -> {
                val k = if (max > 1f) 10f else 100f
                val n = (v * k).roundToInt() / k
                if (abs(n - s.value) > 1e-6f) s.value = n
            }
            else -> {}
        }
    }

    // ---- enums ----

    private fun enumRow(s: EnumSetting<*>, id: String, lx: Float, y: Float, right: Float) {
        val cy = y + 9.5f
        if (segmented(s)) {
            label(s, lx, cy, right - lx)
            val labels = s.constants.map { GuiData.enumName(it) }
            Ui.seg(id, lx, y + 18f, right - lx, 15f, labels, s.constants.indexOf(s.value)) { i ->
                s.select(s.constants[i])
                Sounds.SWITCH_RIGHT.play()
            }
            return
        }
        val text = GuiData.enumName(s.value)
        val bw = min(88f, Type.sub.width(text) + 19f)
        label(s, lx, cy, right - bw - lx - 6f)
        val open = (ModernGui.popover as? EnumMenu)?.setting === s
        val over = Ui.hit("dd:$id", right - bw, cy - 6.5f, bw, 13f, { b ->
            if (b == Keys.MOUSE_LEFT) ModernGui.popover = if (open) null else EnumMenu(s, right - bw, cy + 8.5f, bw)
            else if (b == Keys.MOUSE_MIDDLE) reset(s)
        })
        Ui.box(right - bw, cy - 6.5f, bw, 13f, 4f, if (over || open) 0x1AFFFFFF else Tok.FILL2, 0.5f, if (over || open) Tok.LINE2 else Tok.LINE)
        Ui.text(Type.sub, Ui.fit(Type.sub, text, bw - 18f), right - bw + 5.5f, cy, Tok.T1)
        Ui.icon("down", right - 10f, cy - 3f, 6f, Tok.T3)
    }

    // ---- groups ----

    private fun group(m: Module, s: GroupSetting, id: String, x: Float, y: Float, w: Float) {
        val members = m.settings.filter { it.parent === s.value && it !is GroupSetting }
        val changed = members.count { GuiData.changed(it) }
        val lx = x + 6f
        val right = x + w - 6f
        if (s.value.mode == GroupMode.BOOLS) {
            // a row of chips, one per boolean member
            label(s, lx, y + 9.5f, right - lx)
            var cx = lx
            var cy = y + 19f
            for (b in members.filterIsInstance<BoolSetting>()) {
                if (!b.visible()) continue
                val name = GuiData.name(b)
                val cw = Type.small.width(name) + 15f
                if (cx + cw > right) {
                    cx = lx
                    cy += 15f
                }
                val over = Ui.hit("chip:" + b.id + "@" + m.id, cx, cy, cw, 13f, { bt ->
                    if (bt == Keys.MOUSE_LEFT) toggle(b) else if (bt == Keys.MOUSE_MIDDLE) reset(b)
                })
                if (over) hover = b
                val on = b.value
                Ui.box(cx, cy, cw, 13f, 4f, if (on) 0x1FFFFFFF else Tok.FILL, 0.5f, if (on) 0x38FFFFFF else if (over) Tok.LINE2 else Tok.LINE)
                Ui.box(cx + 5f, cy + 5f, 3f, 3f, 1.5f, if (on) Tok.a1 else 0x33FFFFFF)
                Ui.text(Type.small, name, cx + 11f, cy + 6.5f, if (on) Tok.WHITE else if (over) Tok.T1 else Tok.T3)
                cx += cw + 2.5f
            }
            return
        }
        val open = s.value.opened
        val h = 19f
        val over = Ui.hit("grp:$id", x, y + 0.5f, w, h, { b ->
            if (b == Keys.MOUSE_LEFT) {
                s.value.opened = !open
                if (open) Sounds.SETTINGS_CLOSE.play() else Sounds.SETTINGS_OPEN.play()
            }
        })
        if (over) Ui.box(x, y + 0.5f, w, h, 5f, 0x08FFFFFF)
        val rot = Ui.anim("chev:$id", if (open) 1f else 0f, 380)
        Ui.icon(if (rot > 0.5f) "down" else "chev", lx - 0.5f, y + 6.5f, 7f, Tok.T3)
        Ui.text(Type.semi, GuiData.name(s), lx + 10f, y + 10f, Tok.T1)
        // how many settings it holds, or how many of them are off their defaults
        val visible = members.count { it.visible() && GuiData.valued(it) }
        val tag = if (changed > 0) "$changed changed" else "$visible"
        Ui.textR(Type.chip, tag, right, y + 10f, if (changed > 0) Tok.T2 else Tok.T4)
    }

    private fun chipRows(s: GroupSetting, w: Float): Int {
        val m = ModernGui.selected ?: return 1
        var rows = 1
        var cx = 6f
        for (b in m.settings.filter { it.parent === s.value && it is BoolSetting && it.visible() }) {
            val cw = Type.small.width(GuiData.name(b)) + 15f
            if (cx + cw > w - 6f) {
                rows++
                cx = 6f
            }
            cx += cw + 2.5f
        }
        return rows
    }

    // ---- block, item and entity lists ----

    private fun listRows(s: ListSetting<*>, w: Float): Int {
        var rows = 1
        var cx = 6f
        for (n in Popovers.names(s) + "+ Add") {
            val cw = Type.chip.width(n) + 16f
            if (cx + cw > w - 6f) {
                rows++
                cx = 6f
            }
            cx += cw + 2.5f
        }
        return rows
    }

    private fun listRow(s: ListSetting<*>, id: String, lx: Float, y: Float, right: Float) {
        val cy = y + 9.5f
        label(s, lx, cy, right - lx - 50f)
        val n = s.value.size
        Ui.textR(Type.chip, if (n == 0) "none" else "$n selected", right, cy, Tok.T3)
        var cx = lx
        var ry = y + 19f
        val names = Popovers.names(s)
        for ((i, name) in names.withIndex()) {
            val cw = Type.chip.width(name) + 16f
            if (cx + cw > right) {
                cx = lx
                ry += 13f
            }
            Ui.box(cx, ry, cw, 11f, 3f, Tok.FILL, 0.5f, Tok.LINE)
            Ui.text(Type.chip, name, cx + 4f, ry + 5.5f, Tok.T2)
            val xo = Ui.hit("lx:$id:$i", cx + cw - 9.5f, ry + 2f, 7f, 7f, { b -> if (b == Keys.MOUSE_LEFT) Popovers.remove(s, i) })
            if (xo) Ui.box(cx + cw - 9.5f, ry + 2f, 7f, 7f, 2f, Tok.FILL2)
            Ui.icon("x", cx + cw - 8.5f, ry + 3f, 5f, if (xo) Tok.T1 else Tok.T3)
            cx += cw + 2.5f
        }
        val aw = Type.chip.width("Add") + 15f
        if (cx + aw > right) {
            cx = lx
            ry += 13f
        }
        val ax = cx
        val ay = ry
        val ao = Ui.hit("ladd:$id", ax, ay, aw, 11f, { b -> if (b == Keys.MOUSE_LEFT) ModernGui.popover = ListPicker(s, ax, ay + 13f) })
        Ui.box(ax, ay, aw, 11f, 3f, if (ao) Tok.FILL2 else 0, 0.5f, if (ao) 0x38FFFFFF else Tok.LINE2)
        Ui.icon("plus", ax + 3.5f, ay + 3f, 5f, if (ao) Tok.T1 else Tok.T2)
        Ui.text(Type.chip, "Add", ax + 10f, ay + 5.5f, if (ao) Tok.T1 else Tok.T2)
    }

    /** A setting's value in words (the search results' second line). */
    fun valueText(s: Setting<*>): String = when (s) {
        is BoolSetting -> if (s.value) "on" else "off"
        is EnumSetting<*> -> GuiData.enumName(s.value)
        is IntSetting -> GuiData.number(s, s.value)
        is FloatSetting -> GuiData.number(s, s.value)
        is ColorSetting -> GuiData.hex(s.value.rgb)
        is BindSetting -> Keys.name(s.value)
        is StringSetting -> if (s.value.isEmpty()) "empty" else "\"${s.value}\""
        is ListSetting<*> -> "${s.value.size} entries"
        else -> ""
    }
}
