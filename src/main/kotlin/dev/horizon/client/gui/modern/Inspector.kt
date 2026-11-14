package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.setting.Action
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
 * The new ClickGUI's right column: the selected module's header (name, switch, description, bind, hold, Array List eye,
 * favourite, reset) and its settings, one row each in declaration order, the way the mockup lays them out. Rows slide open and
 * shut as their visibility changes; groups open and close; a middle click puts a setting back to its default.
 */
internal object Inspector {
    private var scroll = 0f
    private var scrollMax = 0f
    private var hover: Setting<*>? = null
    /** The module whose Array List eye the pointer is on, for the footer. */
    private var arrayListHover: Module? = null
    private var flash: Setting<*>? = null
    private var flashAt = 0L
    private var revealing: Setting<*>? = null
    private var revealY = -1f

    /** Text fields: a string setting's, or a number's while its value is being typed. */
    private val fields = HashMap<Setting<*>, Ui.Field>()
    private var typing: Setting<*>? = null

    private val mc get() = net.minecraft.client.Minecraft.getInstance()

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
        Ui.box(x, y, 0.5f, h, 0f, Tok.LINE)
        Ui.box(x + 0.5f, y, w - 0.5f, h, 0f, 0x24000000)
        val m = ModernGui.selected
        if (m == null) {
            empty(x, y, w, h)
            return
        }
        arrayListHover = null
        val headH = header(m, x, y, w)
        val footH = 17.5f
        val by = y + headH
        val bh = h - headH - footH
        hover = null
        body(m, x, by, w, bh)
        footer(m, x, y + h - footH, w, footH)
    }

    private fun empty(x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w / 2f
        var cy = y + h / 2f - 50f
        Ui.mark(cx - 17f, cy - 17f, 34f)
        cy += 25f
        Ui.textC(Type.semi, "Pick a module", cx, cy, Tok.T1)
        cy += 10f
        for (l in Ui.wrap(Type.small, "Right click a module, or press its gear, to see its settings here.", w - 40f, 3)) {
            Ui.textC(Type.small, l, cx, cy, Tok.T3)
            cy += 7.5f
        }
        val on = ModernGui.mods(ModernGui.View.Active).take(12)
        if (on.isEmpty()) return
        cy += 6f
        Ui.textC(Type.section, "ENABLED", cx, cy, Tok.T4)
        cy += 7f
        var rowX = x + 12f
        for (mod in on) {
            val label = GuiData.name(mod)
            val cw = Type.chip.width(label) + 9f
            if (rowX + cw > x + w - 12f) {
                rowX = x + 12f
                cy += 14f
            }
            val over = Ui.hit("emptychip:" + mod.id, rowX, cy, cw, 12f, { if (it == Keys.MOUSE_LEFT) ModernGui.jumpTo(mod) })
            Ui.box(rowX, cy, cw, 12f, 3.5f, Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
            Ui.textC(Type.chip, label, rowX + cw / 2f, cy + 6f, if (over) Tok.T1 else Tok.T2)
            rowX += cw + 3f
        }
    }

    private fun header(m: Module, x: Float, y: Float, w: Float): Float {
        val px = x + 10f
        val pw = w - 20f
        val desc = GuiData.description(m)
        val lines = if (desc.isNotEmpty()) Ui.wrap(Type.body, desc, pw, 3) else emptyList()
        val metaY = y + 34.5f + lines.size * 9.0625f + (if (lines.isEmpty()) 0f else 6f)
        val headH = metaY + 14f + 7f - y
        // radial-gradient(120% 90% at 85% 0%, the accent 24%, transparent 70%) while the module is on
        val glow = Ui.anim("inspOn", if (m.enabled) 1f else 0f, 600)
        if (glow > 0.01f) {
            Ui.pushClip(x, y, w, headH)
            Canvas.radial(x + w * 0.85f, y, w * 0.84f, headH * 0.63f, Tok.alpha(Tok.a1, 0.24f * glow))
            Ui.popClip()
        }
        val cat = Categories.ALL.first { m.subCategory in it.subCategories }.displayName.let { if (it == "Pvp") "PvP" else it }
        val sub = if (m.subCategory.id == "esp") "ESP" else m.subCategory.displayName
        var cy = y + 12.25f
        Ui.text(Type.crumb, cat.uppercase(), px, cy, Tok.T4)
        val cw = Type.crumb.width(cat.uppercase())
        Ui.icon("chev", px + cw + 3f, cy - 2.75f, 5.5f, Tok.T4)
        Ui.text(Type.crumb, sub.uppercase(), px + cw + 11.5f, cy, Tok.T4)
        cy = y + 25f
        Ui.text(Type.h2, Ui.fit(Type.h2, GuiData.name(m), pw - 27f), px, cy, Tok.T1)
        Ui.switch("insp:" + m.id, px + pw - 22f, y + 18.5f, m.enabled, big = true) { m.toggle() }
        for ((i, l) in lines.withIndex()) Ui.text(Type.body, l, px, y + 34.5f + 4.53f + i * 9.0625f, Tok.T2)
        cy = metaY
        // bind, hold, favourite, reset, close
        val b = m.bind
        val capturing = ModernGui.capture?.setting === m.bindSetting
        var bx = px
        bx += Ui.button("bind", bx, cy, 14f, if (capturing) "Press a key..." else if (b.key == -1) "No bind" else dev.horizon.client.gui.Keys.name(b), "key",
            active = capturing) { ModernGui.startCapture(m, m.bindSetting) } + 3f
        Ui.button("hold", bx, cy, 14f, if (b.hold) "Hold" else "Toggle", null) {
            m.bindSetting.value = m.bind.copy(hold = !b.hold)
            Sounds.SWITCH_RIGHT.play()
        }
        var ix = px + pw - 14f
        Ui.button("close", ix, cy, 14f, null, "x") { ModernGui.select(null) }
        ix -= 17f
        Ui.button("reset", ix, cy, 14f, null, "reset") {
            m.settings.forEach { if (it.id !in Module.META) it.reset() }
            Sounds.SETTINGS_CLOSE.play()
        }
        ix -= 17f
        val fav = GuiData.isFav(m)
        val fo = Ui.hit("btn:fav", ix, cy, 14f, 14f, { if (it == Keys.MOUSE_LEFT) GuiData.toggleFav(m) })
        Ui.box(ix, cy, 14f, 14f, 4f, if (fo) Tok.FILL2 else Tok.FILL, 0.5f, if (fo) Tok.LINE2 else Tok.LINE)
        Ui.icon(if (fav) "favon" else "fav", ix + 3.5f, cy + 3.5f, 7f, if (fav) 0xFFFFC94D.toInt() else if (fo) Tok.T1 else Tok.T2)
        // Shown in the Array List or not (the footer says which while the pointer is on it).
        ix -= 17f
        val hid = m.listHidden
        val ho = Ui.hit("btn:hidden", ix, cy, 14f, 14f, {
            if (it == Keys.MOUSE_LEFT) {
                m.listHidden = !hid
                Sounds.SWITCH_RIGHT.play()
            }
        })
        if (ho) arrayListHover = m
        Ui.box(ix, cy, 14f, 14f, 4f, if (ho) Tok.FILL2 else Tok.FILL, 0.5f, if (ho) Tok.LINE2 else Tok.LINE)
        Ui.icon(if (hid) "eyeoff" else "eye", ix + 3.25f, cy + 3.25f, 7.5f, if (ho) Tok.T1 else if (hid) Tok.T3 else Tok.T2)
        Ui.box(x, y + headH, w, 0.5f, 0f, Tok.LINE)
        return headH + 0.5f
    }

    private fun footer(m: Module, x: Float, y: Float, w: Float, h: Float) {
        Ui.box(x, y, w, 0.5f, 0f, Tok.LINE)
        val cy = y + h / 2f
        val s = hover
        var tx = x + 9.5f
        fun part(text: String, strong: Boolean) {
            val font = if (strong) Type.statusB else Type.status
            Ui.text(font, text, tx, cy, if (strong) Tok.T2 else Tok.T3)
            tx += font.width(text) + 4f
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
            part("changed from default", false)
        }
    }

    // ---- the settings ----

    /** A row to draw: the setting, its nesting, and its full height. */
    private class Row(val s: Setting<*>, val depth: Int, val height: Float, val visible: Boolean)

    private fun body(m: Module, x: Float, y: Float, w: Float, h: Float) {
        val left = x + 5f
        val width = w - 10f
        // the layout: top-level settings, a group's members under it while it is open
        val rows = ArrayList<Row>()
        val groups = HashMap<Any, GroupSetting>()
        for (s in m.settings) if (s is GroupSetting) groups[s.value] = s
        for (s in m.settings) {
            if (s.id in Module.META) continue
            val g = s.parent?.let { groups[it] }
            if (g != null) {
                if (g.value.mode == GroupMode.BOOLS) continue
                rows += Row(s, 1, rowHeight(s, width - 12f), s.visible() && g.visible())
            } else {
                rows += Row(s, 0, rowHeight(s, width), s.visible())
            }
        }
        // total height, with the open/close and show/hide animations
        var total = 4f
        val shown = ArrayList<Pair<Row, Float>>()
        for (r in rows) {
            val g = r.s.parent?.let { groups[it] }
            val openF = if (g != null) Ui.anim("grp:" + g.id + "@" + m.id, if (g.value.opened) 1f else 0f, 450) else 1f
            val visF = Ui.anim("row:" + r.s.id + "@" + m.id, if (r.visible) 1f else 0f, 380)
            val f = openF * visF
            shown += r to f
            total += r.height * f
        }
        total += 10f
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
        var lastGroupTop = -1f
        for ((r, f) in shown) {
            if (f < 0.01f) continue
            val rh = r.height * f
            if (ry + rh >= y - 2f && ry <= y + h + 2f) {
                val rx = if (r.depth == 1) left + 12f else left
                val rw = if (r.depth == 1) width - 12f else width
                if (r.depth == 1) Ui.box(left + 8.5f, ry, 0.5f, rh, 0f, Tok.LINE)
                val pa = Canvas.alpha
                Canvas.alpha = pa * f
                if (f < 0.999f) Ui.pushClip(rx - 2f, ry, rw + 4f, rh)
                row(m, r.s, rx, ry, rw, r.height, groups)
                if (f < 0.999f) Ui.popClip()
                Canvas.alpha = pa
            }
            ry += rh
            if (r.s is GroupSetting) lastGroupTop = ry
        }
        Ui.popClip()
        Ui.fade(ModernGui.inspectorBackdrop, x + 1f, y, w - 2f, 8f, false, sy / 8f)
        Ui.fade(ModernGui.inspectorBackdrop, x + 1f, y + h, w - 2f, 8f, true, (scrollMax - sy) / 8f)
        if (scrollMax > 0f) ModernGui.scrollbar(x + w - 3.5f, y + 1.5f, h - 3f, sy, scrollMax)
    }

    private fun rowHeight(s: Setting<*>, w: Float): Float = when (s) {
        is BoolSetting, is ColorSetting, is BindSetting -> 20f
        is IntSetting, is FloatSetting -> 35f
        is EnumSetting<*> -> if (segmented(s)) 39.5f else 20f
        is StringSetting -> 39f
        is ActionSetting -> 24f
        is NotationSetting -> 8f + Ui.wrap(Type.small, s.value.text, w - 26f, 8).size * 7.5f + 10f
        is GroupSetting -> if (s.value.mode == GroupMode.BOOLS) 20f + chipRows(s, w) * 15.5f + 2f else 21f
        is ListSetting<*> -> 20f + listRows(s, w) * 13f + 4f
        else -> 20f
    }

    private fun segmented(s: EnumSetting<*>) = s.constants.size <= 3 && s.constants.sumOf { it.name.length } <= 24

    private fun row(m: Module, s: Setting<*>, x: Float, y: Float, w: Float, h: Float, groups: Map<Any, GroupSetting>) {
        val id = s.id + "@" + m.id
        val inside = Ui.mx >= x && Ui.mx <= x + w && Ui.my >= y && Ui.my <= y + h && ModernGui.popover?.contains(Ui.mx, Ui.my) != true
        if (inside) hover = s
        val flashing = flash === s && System.currentTimeMillis() - flashAt in -250..1400
        val fl = if (flashing) (1f - max(0L, System.currentTimeMillis() - flashAt) / 1400f) else 0f
        if (s !is GroupSetting && (inside || fl > 0f)) {
            Ui.box(x, y, w, h, 5f, Colors.lerp(0x08FFFFFF, Tok.alpha(Tok.a1, 0.22f), fl))
        }
        // middle click on the row: back to the default
        if (GuiData.valued(s)) Ui.hit("row:$id", x, y, w, h, { b -> if (b == Keys.MOUSE_MIDDLE) reset(s) }, pointer = false)
        val lx = x + 11f
        val cy = y + 10f
        val right = x + w - 5f
        when (s) {
            is BoolSetting -> {
                label(s, lx, cy, right - 20f - lx)
                val over = Ui.hit("bool:$id", x, y, w, h, { b -> if (b == Keys.MOUSE_LEFT) toggle(s) else if (b == Keys.MOUSE_MIDDLE) reset(s) })
                Ui.switch(id, right - 17f, cy - 5f, s.value)
            }
            is IntSetting, is FloatSetting -> slider(s, id, lx, x, y, w, right)
            is EnumSetting<*> -> enumRow(s, id, lx, x, y, w, right)
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
                Ui.button("bind:$id", right - bw, cy - 7f, 14f, name, "key", active = capturing) { ModernGui.startCapture(m, s) }
            }
            is StringSetting -> {
                label(s, lx, cy, w - 20f)
                val f = fields.getOrPut(s) { Ui.Field(s.value, 256) { t -> s.value = t } }
                if (Ui.focused !== f) f.text = s.value
                val fy = y + 20f
                val focused = Ui.focused === f
                Ui.hit(Ui.fieldId(f), x + 5f, fy, w - 10f, 15f, { b -> if (b == Keys.MOUSE_LEFT) Ui.focused = f })
                Ui.box(x + 5f, fy, w - 10f, 15f, 4f, 0x40000000, 0.5f, if (focused) Tok.alpha(Tok.a1, 0.55f) else Tok.LINE2)
                Ui.fieldText(f, x + 10f, fy + 7.5f, w - 20f, Type.label, "Empty")
            }
            is ActionSetting -> {
                val over = Ui.hit("act:$id", x + 5f, y + 4f, w - 10f, 16f, { b ->
                    if (b == Keys.MOUSE_LEFT) {
                        s.value.run()
                        Sounds.SWITCH_RIGHT.play()
                        // Actions that open a popup (Inventory Manager's kits) show it on its own screen.
                        if (dev.horizon.client.gui.popup.PopupScreen.popup != null) Minecraft.getInstance().gui.setScreen(dev.horizon.client.gui.popup.PopupScreen)
                    }
                })
                Ui.box(x + 5f, y + 4f, w - 10f, 16f, 4.5f, Tok.alpha(Tok.a1, if (over) 0.3f else 0.2f), 0.5f, Tok.alpha(Tok.a1, 0.35f))
                val tw = Type.semi.width(GuiData.name(s))
                Ui.icon("play", x + w / 2f - tw / 2f - 6f, y + 9f, 6f, Tok.T1)
                Ui.text(Type.semi, GuiData.name(s), x + w / 2f - tw / 2f + 3f, y + 12f, Tok.T1)
            }
            is NotationSetting -> {
                Ui.box(x + 5f, y + 4f, w - 10f, h - 8f, 5f, Tok.FILL, 0.5f, Tok.LINE)
                Ui.icon("info", x + 10f, y + 9f, 7f, Tok.light(Tok.a1, 0.3f))
                var ly = y + 12f
                for (l in Ui.wrap(Type.small, s.value.text, w - 26f, 8)) {
                    Ui.text(Type.small, l, x + 21f, ly, Tok.T2)
                    ly += 7.5f
                }
            }
            is GroupSetting -> group(m, s, id, x, y, w, h, groups)
            is ListSetting<*> -> listRow(s, id, lx, x, y, w, right)
            else -> label(s, lx, cy, w - 20f)
        }
    }

    private fun label(s: Setting<*>, x: Float, cy: Float, maxW: Float) {
        val name = Ui.fit(Type.label, GuiData.name(s), maxW - 6f)
        Ui.text(Type.label, name, x, cy, 0xDBF5F5F7.toInt())
        if (GuiData.changed(s)) {
            val t = Ui.anim("dot:" + System.identityHashCode(s), 1f, 350, dev.horizon.client.render.Easing.OUT_BACK)
            val r = 1.25f * t
            val dx = x + Type.label.width(name) + 3.5f + (1.25f - r)
            Ui.glow(dx, cy - r, r * 2, r * 2, r, 3f, Tok.alpha(Tok.a1, 0.6f * t))
            Ui.box(dx, cy - r, r * 2, r * 2, r, Tok.a1)
        }
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

    @Suppress("UNCHECKED_CAST")
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

    private fun slider(s: Setting<*>, id: String, lx: Float, x: Float, y: Float, w: Float, right: Float) {
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
        val cy = y + 10f
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
            Ui.box(right - vw + 3f, cy - 5f, vw, 10f, 3f, 0x66000000, 0.5f, Tok.alpha(Tok.a1, 0.6f))
            Ui.fieldText(f, right - vw + 6f, cy, vw, Type.value, "")
        } else {
            if (vOver) Ui.box(right - vw + 3f, cy - 5f, vw, 10f, 3f, Tok.FILL2)
            Ui.textR(Type.value, valueText, right, cy, Tok.T1)
        }
        // the track: filled part, a gap, the handle, a gap, the rest
        val tx = x + 5f
        val tw = w - 10f
        val ty = y + 19f
        val frac = ((value - min) / span).coerceIn(0f, 1f)
        val anim = Ui.anim("sl:$id", frac, 300)
        val dragging = draggingSlider == s
        val f2 = if (dragging) frac else anim
        val hx = tx + 1f + (tw - 2f) * f2
        val over = Ui.hit("slider:$id", tx, ty - 1f, tw, 15f, { b -> if (b == Keys.MOUSE_MIDDLE) reset(s) },
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
        val gap = 3f
        val actW = hx - gap - tx
        if (actW > 2.5f) {
            Ui.glow(tx, ty + 3f, actW, 7f, 3.5f, 5f, Tok.alpha(Tok.a1, 0.25f))
            Ui.hbox(tx, ty + 3f, actW, 7f, 3.5f, Tok.a1, Tok.light(Tok.a2, 0.22f))
        }
        val inaX = hx + gap
        val inaW = tx + tw - inaX
        if (inaW > 2.5f) {
            Ui.box(inaX, ty + 3f, inaW, 7f, 3.5f, if (over) 0x1FFFFFFF else 0x17FFFFFF)
            Ui.box(tx + tw - 4.5f, ty + 5.5f, 2f, 2f, 1f, 0x59FFFFFF)
        }
        val dfx = tx + 1f + (tw - 2f) * ((def - min) / span).coerceIn(0f, 1f)
        Ui.box(dfx - 1f, ty + 5.5f, 2f, 2f, 1f, 0x80FFFFFF.toInt())
        val hw = if (dragging) 1f else 2f
        Ui.box(hx - hw / 2f, ty, hw, 13f, hw / 2f, Tok.WHITE)
        if (dragging) {
            val tip = valueText
            val tipW = Type.tip.width(tip) + 8f
            val tipX = (hx - tipW / 2f)
            Ui.box(tipX, ty - 14f, tipW, 11f, 3.5f, Tok.WHITE)
            // the arrow under it
            for (k in 0 until 4) Ui.box(hx - 2f + k * 0.5f, ty - 3f + k * 0.5f, 4f - k, 0.5f, 0f, Tok.WHITE)
            Ui.textC(Type.tip, tip, hx, ty - 8.5f, 0xFF111111.toInt())
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

    private fun enumRow(s: EnumSetting<*>, id: String, lx: Float, x: Float, y: Float, w: Float, right: Float) {
        val cy = y + 10f
        if (segmented(s)) {
            label(s, lx, cy, w - 20f)
            val labels = s.constants.map { GuiData.enumName(it) }
            Ui.seg(id, x + 5f, y + 19.5f, w - 10f, 16f, labels, s.constants.indexOf(s.value)) { i ->
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

    private fun group(m: Module, s: GroupSetting, id: String, x: Float, y: Float, w: Float, h: Float, groups: Map<Any, GroupSetting>) {
        val members = m.settings.filter { it.parent === s.value && it !is GroupSetting }
        val changed = members.count { GuiData.changed(it) }
        if (s.value.mode == GroupMode.BOOLS) {
            // a row of chips, one per boolean member
            label(s, x + 11f, y + 10f, w - 20f)
            var cx = x + 5f
            var cy = y + 20f
            for (b in members.filterIsInstance<BoolSetting>()) {
                if (!b.visible()) continue
                val name = GuiData.name(b)
                val cw = Type.small.width(name) + 15f
                if (cx + cw > x + w - 5f) {
                    cx = x + 5f
                    cy += 15.5f
                }
                val over = Ui.hit("chip:" + b.id + "@" + m.id, cx, cy, cw, 13f, { bt ->
                    if (bt == Keys.MOUSE_LEFT) toggle(b) else if (bt == Keys.MOUSE_MIDDLE) reset(b)
                })
                if (over) hover = b
                val on = b.value
                Ui.box(cx, cy, cw, 13f, 4f, if (on) Tok.alpha(Tok.a1, 0.18f) else Tok.FILL, 0.5f,
                    if (on) Tok.alpha(Tok.a1, 0.45f) else if (over) Tok.LINE2 else Tok.LINE)
                Ui.box(cx + 5f, cy + 5f, 3f, 3f, 1.5f, if (on) Tok.a1 else 0x33FFFFFF)
                Ui.text(Type.small, name, cx + 11f, cy + 6.5f, if (on) Tok.WHITE else if (over) Tok.T1 else Tok.T3)
                cx += cw + 2.5f
            }
            return
        }
        val open = s.value.opened
        val y = y + 1f
        val h = 19f
        val over = Ui.hit("grp:$id", x, y, w, h, { b ->
            if (b == Keys.MOUSE_LEFT) {
                s.value.opened = !open
                if (open) Sounds.SETTINGS_CLOSE.play() else Sounds.SETTINGS_OPEN.play()
            }
        })
        if (over) Ui.box(x, y, w, h, 5f, 0x09FFFFFF)
        val rot = Ui.anim("chev:$id", if (open) 1f else 0f, 450)
        Ui.icon(if (rot > 0.5f) "down" else "chev", x + 5f, y + 6f, 7f, Tok.T3)
        Ui.text(Type.semi, GuiData.name(s), x + 16f, y + 9.5f, Tok.T1)
        val visible = members.count { it.visible() && GuiData.valued(it) }
        val tag = if (changed > 0) "$changed changed" else "$visible"
        val tw = Type.chip.width(tag) + 7f
        Ui.box(x + w - 5f - tw, y + 5.25f, tw, 8.5f, 3.5f, if (changed > 0) Tok.alpha(Tok.a1, 0.13f) else Tok.FILL)
        Ui.textC(Type.chip, tag, x + w - 5f - tw / 2f, y + 9.5f, if (changed > 0) Tok.light(Tok.a1, 0.3f) else Tok.T3)
    }

    private fun chipRows(s: GroupSetting, w: Float): Int {
        val m = ModernGui.selected ?: return 1
        var rows = 1
        var cx = 5f
        for (b in m.settings.filter { it.parent === s.value && it is BoolSetting && it.visible() }) {
            val cw = Type.small.width(GuiData.name(b)) + 15f
            if (cx + cw > w - 5f) {
                rows++
                cx = 5f
            }
            cx += cw + 2.5f
        }
        return rows
    }

    // ---- block, item and entity lists ----

    private fun listRows(s: ListSetting<*>, w: Float): Int {
        var rows = 1
        var cx = 5f
        for (n in Popovers.names(s) + "+ Add") {
            val cw = Type.chip.width(n) + 16f
            if (cx + cw > w - 5f) {
                rows++
                cx = 5f
            }
            cx += cw + 2.5f
        }
        return rows
    }

    private fun listRow(s: ListSetting<*>, id: String, lx: Float, x: Float, y: Float, w: Float, right: Float) {
        val cy = y + 10f
        label(s, lx, cy, w - 60f)
        val n = s.value.size
        Ui.textR(Type.chip, if (n == 0) "none" else "$n selected", right, cy, Tok.T3)
        var cx = x + 5f
        var ry = y + 20f
        val names = Popovers.names(s)
        for ((i, name) in names.withIndex()) {
            val cw = Type.chip.width(name) + 16f
            if (cx + cw > x + w - 5f) {
                cx = x + 5f
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
        if (cx + aw > x + w - 5f) {
            cx = x + 5f
            ry += 13f
        }
        val ao = Ui.hit("ladd:$id", cx, ry, aw, 11f, { b -> if (b == Keys.MOUSE_LEFT) ModernGui.popover = ListPicker(s, cx, ry + 13f) })
        Ui.box(cx, ry, aw, 11f, 3f, if (ao) Tok.alpha(Tok.a1, 0.12f) else 0, 0.5f, Tok.alpha(Tok.a1, 0.5f))
        Ui.icon("plus", cx + 3.5f, ry + 3f, 5f, Tok.light(Tok.a1, 0.3f))
        Ui.text(Type.chip, "Add", cx + 10f, ry + 5.5f, Tok.light(Tok.a1, 0.3f))
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
