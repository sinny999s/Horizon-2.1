package dev.horizon.client.gui.modern

import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.module.legit.GhostMode
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Theme
import dev.horizon.client.render.Themes
import dev.horizon.client.setting.BlocksSetting
import dev.horizon.client.setting.ColorSetting
import dev.horizon.client.setting.EntitiesSetting
import dev.horizon.client.setting.EnumSetting
import dev.horizon.client.setting.ItemsSetting
import dev.horizon.client.setting.ListSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import java.awt.Color
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A popover of the new ClickGUI: a glass card over everything, closed by a click outside it (or Esc). [draw] lays it
 * out and returns false to close it.
 */
internal abstract class Popover {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f

    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    open fun anchorContains(px: Float, py: Float) = false

    abstract fun draw(): Boolean

    /**
     * Keeps the card in the window (above its anchor when it doesn't fit below) and draws its glass. [scrim] dims the
     * window under it first: for a card big enough to lie across several of the window's parts, whose cut-off text
     * would crowd its edges.
     */
    protected fun card(ax: Float, ay: Float, cw: Float, ch: Float, anchorTop: Float = ay - 20f, scrim: Boolean = false) {
        w = cw
        h = ch
        x = ax.coerceIn(4f, ModernGui.W - 4f - cw)
        y = if (ay + ch > ModernGui.H - 4f) max(4f, anchorTop - ch - 4f) else ay
        val o = Modules.get<ClickGuiModule>().opacity.value
        if (scrim) Ui.box(0f, 0f, ModernGui.W, ModernGui.H, 11f, 0x57000000)
        // box-shadow: 0 24px 60px -12px rgba(0, 0, 0, .75)
        Ui.glow(x + 6f, y + 18f, w - 12f, h - 12f, 6.5f, 17f, 0x7C000000)
        Ui.blur(x, y, w, h, 6.5f)
        Ui.box(x, y, w, h, 6.5f, Colors.withAlpha(0xFF18161F.toInt(), o + 0.12f), 0.5f, Tok.LINE2)
        Ui.block("pop", x, y, w, h)
    }

    protected fun option(id: String, ox: Float, oy: Float, ow: Float, label: String, checked: Boolean, icon: String? = null,
                         color: Int = Tok.T2, click: () -> Unit): Boolean {
        val over = Ui.hit(id, ox, oy, ow, 15f, { if (it == Keys.MOUSE_LEFT) click() })
        if (over) Ui.box(ox, oy, ow, 15f, 4f, Tok.FILL2)
        var tx = ox + 5f
        if (icon != null) {
            Ui.icon(icon, tx, oy + 4f, 7f, if (over || checked) Tok.T1 else color)
            tx += 11f
        }
        Ui.text(Type.label, Ui.fit(Type.label, label, ow - (tx - ox) - 16f), tx, oy + 7.5f, if (checked) Tok.WHITE else if (over) Tok.T1 else color)
        if (checked) Ui.icon("check", ox + ow - 12f, oy + 4f, 7f, Tok.light(Tok.a1, 0.2f))
        return over
    }
}

/** An enum setting's constants. */
internal class EnumMenu(val setting: EnumSetting<*>, private val ax: Float, private val ay: Float, private val minW: Float) : Popover() {
    private var scroll = 0f

    override fun anchorContains(px: Float, py: Float) = px >= ax && px <= ax + minW && py >= ay - 15f && py <= ay

    override fun draw(): Boolean {
        val cs = setting.constants
        val labels = cs.map { GuiData.enumName(it) }
        val cw = max(minW, (labels.maxOfOrNull { Type.label.width(it) } ?: 40f) + 34f)
        val shownH = min(cs.size, 9) * 15f
        card(ax + minW - cw, ay, cw, shownH + 6f, ay - 13f)
        val maxScroll = max(0f, cs.size * 15f - shownH)
        Ui.hit("enumlist", x, y, w, h, scroll = { a -> scroll = (scroll - a.toFloat() * 15f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, y + 3f, w, shownH)
        for ((i, c) in cs.withIndex()) {
            val oy = y + 3f + i * 15f - scroll
            if (oy + 15f < y || oy > y + h) continue
            option("enum:$i", x + 3f, oy, w - 6f, labels[i], c == setting.value) {
                setting.select(c)
                Sounds.SWITCH_RIGHT.play()
                ModernGui.popover = null
            }
        }
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + w - 4f, y + 4f, shownH - 2f, scroll, maxScroll)
        return true
    }
}

/** A colour setting's picker: saturation/brightness square, hue and alpha bars, hex, and a few ready colours. */
internal class ColorPicker(private val setting: ColorSetting, private val ax: Float, private val ay: Float,
                           private val rx: Float, private val ry: Float, private val rw: Float, private val rh: Float) : Popover() {
    private var hsb = FloatArray(3)
    private var alpha = 255

    init {
        val c = setting.value
        Color.RGBtoHSB(c.red, c.green, c.blue, hsb)
        alpha = c.alpha
    }

    override fun anchorContains(px: Float, py: Float) = px >= rx && px <= rx + rw && py >= ry && py <= ry + rh

    private fun apply() {
        val rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) and 0xFFFFFF
        setting.value = Color((alpha shl 24) or rgb, true)
    }

    override fun draw(): Boolean {
        card(ax, ay, 118f, 125f, ry)
        val px = x + 4f
        val pw = w - 8f
        var py = y + 4f
        // the square
        val hue = Color.HSBtoRGB(hsb[0], 1f, 1f) or 0xFF000000.toInt()
        Canvas.quad(px, py, pw, 72f, -1, hue, hue, -1)
        Canvas.quad(px, py, pw, 72f, 0xFF000000.toInt(), 0xFF000000.toInt(), 0, 0)
        val sqY = py
        Ui.hit("cp:sv", px, py, pw, 72f, drag = { mx, my ->
            hsb[1] = ((mx - px) / pw).coerceIn(0f, 1f)
            hsb[2] = 1f - ((my - sqY) / 72f).coerceIn(0f, 1f)
            apply()
        })
        knob(px + pw * hsb[1], py + 72f * (1f - hsb[2]), setting.value.rgb or 0xFF000000.toInt())
        py += 77f
        // hue
        val stops = intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
        val seg = pw / 6f
        for (i in 0 until 6) Canvas.quad(px + seg * i, py, seg + 0.3f, 6f, stops[i], stops[i + 1], stops[i + 1], stops[i])
        val hueY = py
        Ui.hit("cp:hue", px, py - 1f, pw, 8f, drag = { mx, _ ->
            hsb[0] = ((mx - px) / pw).coerceIn(0f, 0.999f)
            apply()
        })
        knob(px + pw * hsb[0], hueY + 3f, hue)
        py += 10f
        // alpha
        var cx = px
        var i = 0
        while (cx < px + pw) {
            Canvas.quad(cx, py, min(3f, px + pw - cx), 3f, if (i % 2 == 0) 0xFF888888.toInt() else 0xFF555555.toInt())
            Canvas.quad(cx, py + 3f, min(3f, px + pw - cx), 3f, if (i % 2 == 0) 0xFF555555.toInt() else 0xFF888888.toInt())
            cx += 3f
            i++
        }
        val solid = setting.value.rgb or 0xFF000000.toInt()
        Canvas.quad(px, py, pw, 6f, solid and 0xFFFFFF, solid, solid, solid and 0xFFFFFF)
        val aY = py
        Ui.hit("cp:alpha", px, py - 1f, pw, 8f, drag = { mx, _ ->
            alpha = (((mx - px) / pw).coerceIn(0f, 1f) * 255).toInt()
            apply()
        })
        knob(px + pw * alpha / 255f, aY + 3f, solid)
        py += 10f
        // hex and presets
        Ui.box(px, py, 18f, 13f, 3f, setting.value.rgb, 0.5f, 0x2EFFFFFF)
        Ui.box(px + 21f, py, pw - 21f, 13f, 3.5f, 0x4D000000, 0.5f, Tok.LINE2)
        Ui.text(Type.value, GuiData.hex(setting.value.rgb), px + 25f, py + 6.5f, Tok.T1)
        py += 16f
        val presets = intArrayOf(Theme.primary or 0xFF000000.toInt(), Theme.secondary or 0xFF000000.toInt(), -1, 0xFF000000.toInt(),
            0xFF38F4E0.toInt(), 0xFF9547EF.toInt(), 0xFFFFC94D.toInt(), 0xFF34D77B.toInt())
        val pwEach = (pw - 7 * 2.5f) / 8f
        for ((j, c) in presets.withIndex()) {
            val bx = px + j * (pwEach + 2.5f)
            val over = Ui.hit("cp:p$j", bx, py, pwEach, 9f, { if (it == Keys.MOUSE_LEFT) {
                val cc = Color(c, true)
                Color.RGBtoHSB(cc.red, cc.green, cc.blue, hsb)
                apply()
            } })
            Ui.box(bx, py, pwEach, 9f, 2.5f, c, 0.5f, if (over) 0x80FFFFFF.toInt() else 0x26FFFFFF)
        }
        return true
    }

    private fun knob(kx: Float, ky: Float, fill: Int) {
        Ui.box(kx - 3.5f, ky - 3.5f, 7f, 7f, 3.5f, fill, 1f, Tok.WHITE)
    }
}

/** A block, item or entity list's picker: search, then click to add or take away. */
internal class ListPicker(private val setting: ListSetting<*>, private val ax: Float, private val ay: Float) : Popover() {
    private val field = Ui.Field(max = 32) { scroll = 0f }
    private var scroll = 0f

    init {
        Ui.focused = field
    }

    override fun draw(): Boolean {
        card(ax, ay, 130f, 150f, ay - 13f)
        val f = field
        val focused = Ui.focused === f
        Ui.hit(Ui.fieldId(f), x + 3f, y + 3f, w - 6f, 15f, { if (it == Keys.MOUSE_LEFT) Ui.focused = f })
        Ui.box(x + 3f, y + 3f, w - 6f, 15f, 4f, 0x4D000000, 0.5f, if (focused) Tok.alpha(Tok.a1, 0.55f) else Tok.LINE2)
        Ui.fieldText(f, x + 8f, y + 10.5f, w - 16f, Type.label, "Search ${kind(setting)}...")
        val q = f.text.lowercase(Locale.ROOT).trim()
        val chosen = setting.value.map { Popovers.descId(it) }.toSet()
        val entries = Popovers.entries(setting).filter { (_, id, name) -> q.isEmpty() || q in name.lowercase(Locale.ROOT) || q in id }
            .sortedWith(compareBy({ it.second !in chosen }, { it.third }))
        val listY = y + 21f
        val listH = h - 24f
        val maxScroll = max(0f, entries.size * 15f - listH)
        scroll = scroll.coerceIn(0f, maxScroll)
        Ui.hit("lplist", x, listY, w, listH, scroll = { a -> scroll = (scroll - a.toFloat() * 15f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, listY, w, listH)
        val first = (scroll / 15f).toInt()
        for (i in first until min(entries.size, first + (listH / 15f).toInt() + 2)) {
            val (e, id, name) = entries[i]
            val on = id in chosen
            option("lp:$id", x + 3f, listY + i * 15f - scroll, w - 6f, name, on) {
                if (on) Popovers.removeEntry(setting, e) else Popovers.add(setting, e)
                if (on) Sounds.DISABLE.play() else Sounds.ENABLE.play()
            }
        }
        if (entries.isEmpty()) Ui.textC(Type.small, "Nothing matches", x + w / 2f, listY + 14f, Tok.T3)
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + w - 4f, listY + 1f, listH - 2f, scroll, maxScroll)
        return true
    }

    private fun kind(s: ListSetting<*>) = when (s) {
        is BlocksSetting -> "blocks"
        is ItemsSetting -> "items"
        else -> "entities"
    }
}

/** The title bar's Theme: the saved themes and the window's opacity. */
internal class ThemeMenu(private val ax: Float, private val ay: Float) : Popover() {
    private val ui get() = Modules.get<ClickGuiModule>()

    override fun draw(): Boolean {
        val themes = Themes.list
        val rows = (themes.size + 1) / 2
        card(ax, ay, 157f, 51.5f + rows * 23f)
        Ui.text(Type.section, "THEME", x + 8f, y + 9f, Tok.T4)
        // .themes: two columns of tiles (.th), each its theme's two colours in a diagonal gradient and its name
        val tw = 71.5f
        for ((i, t) in themes.withIndex()) {
            val tx = x + 5.5f + (i % 2) * (tw + 3f)
            val ty = y + 16f + (i / 2) * 23f
            val on = Themes.current === t
            val over = Ui.hit("theme:${t.id}", tx, ty, tw, 20f, { if (it == Keys.MOUSE_LEFT) Theme.select(t) })
            Ui.box(tx, ty, tw, 20f, 5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (on) Tok.alpha(Tok.a1, 0.6f) else Tok.LINE)
            Ui.dbox(tx + 4.5f, ty + 4.5f, 11f, 11f, 3.5f, t.primaryColor or 0xFF000000.toInt(), t.secondaryColor or 0xFF000000.toInt())
            Ui.text(Type.small, Ui.fit(Type.small, t.id, tw - 24f), tx + 20f, ty + 10f, if (on) Tok.WHITE else if (over) Tok.T1 else Tok.T2)
        }
        var py = y + 15f + rows * 23f + 2.5f
        Ui.box(x + 5.5f, py, w - 11f, 0.5f, 0f, Tok.LINE)
        py += 5f
        // window opacity
        val o = ui.opacity
        val min = o.range.start
        val max = o.range.endInclusive
        Ui.text(Type.small, "Window opacity", x + 7f, py + 5f, Tok.T2)
        Ui.textR(Type.value, "${(o.value * 100).toInt()}%", x + w - 7f, py + 5f, Tok.T1)
        val tx = x + 6f
        val twd = w - 12f
        val ty = py + 11f
        Ui.hit("op", tx, ty - 1f, twd, 10f, drag = { mx, _ -> o.value = ((min + (max - min) * ((mx - tx) / twd)) * 100).toInt() / 100f },
            scroll = { a -> o.value = (o.value + if (a > 0) 0.01f else -0.01f).coerceIn(min, max); true })
        val frac = ((o.value - min) / (max - min)).coerceIn(0f, 1f)
        val hx = tx + 1f + (twd - 2f) * frac
        if (hx - 3f - tx > 2.5f) Ui.box(tx, ty + 1.5f, hx - 3f - tx, 6f, 3f, Tok.mid)
        if (tx + twd - hx - 3f > 2.5f) Ui.box(hx + 3f, ty + 1.5f, tx + twd - hx - 3f, 6f, 3f, 0x17FFFFFF)
        Ui.box(hx - 1f, ty - 1f, 2f, 11f, 1f, Tok.WHITE)
        return true
    }
}

/** The sidebar's profile: switch module configs, turn Ghost Mode on or off, or open the Windows and HUD editor. */
internal class ProfileMenu(private val ax: Float, private val ay: Float) : Popover() {
    override fun draw(): Boolean {
        val configs = Configs.of(ConfigType.MODULES).list.toList()
        card(ax, ay - (configs.size * 15f + 80f), 120f, configs.size * 15f + 76f)
        var py = y + 4f
        Ui.text(Type.section, "CONFIGS", x + 6f, py + 5f, Tok.T4)
        py += 10f
        val current = Configs.of(ConfigType.MODULES).current.id
        for (c in configs) {
            option("cfg:${c.id}", x + 3f, py, w - 6f, c.id, c.id == current, "config") {
                if (c.id != current) {
                    Configs.load(c.id, ConfigType.MODULES)
                    Sounds.SWITCH_LEFT.play()
                }
                ModernGui.popover = null
            }
            py += 15f
        }
        Ui.box(x + 4f, py + 2f, w - 8f, 0.5f, 0f, Tok.LINE)
        py += 5f
        // Ghost Mode's switch, saved with the config above.
        val ghost = GhostMode.enabled
        option("ghost", x + 3f, py, w - 6f, "Ghost mode", ghost, "eyeoff") {
            GhostMode.toggle()
            if (ghost) Sounds.DISABLE.play() else Sounds.ENABLE.play()
        }
        py += 15f
        Ui.box(x + 4f, py + 2f, w - 8f, 0.5f, 0f, Tok.LINE)
        py += 5f
        option("open:windows", x + 3f, py, w - 6f, "Windows", false, "windows") { Minecraft.getInstance().gui.setScreen(ModernWindows) }
        py += 15f
        option("open:hud", x + 3f, py, w - 6f, "HUD editor", false, "layout") { Minecraft.getInstance().gui.setScreen(ModernHudEditor) }
        return true
    }
}

/** List settings' registries and names. */
internal object Popovers {
    /** (entry, description id, name) for every entry the list can hold. */
    fun entries(s: ListSetting<*>): List<Triple<Any, String, String>> = cache.getOrPut(s.javaClass) {
        val all: List<Pair<Any, String>> = when (s) {
            is BlocksSetting -> BuiltInRegistries.BLOCK.map { it to it.descriptionId }
            is ItemsSetting -> BuiltInRegistries.ITEM.map { it to it.descriptionId }
            is EntitiesSetting -> BuiltInRegistries.ENTITY_TYPE.map { it to it.descriptionId }
            else -> emptyList()
        }
        all.map { (e, id) -> Triple(e, id, Component.translatable(id).string) }.distinctBy { it.second }
    }

    private val cache = HashMap<Class<*>, List<Triple<Any, String, String>>>()

    fun descId(e: Any?): String = when (e) {
        is net.minecraft.world.level.block.Block -> e.descriptionId
        is net.minecraft.world.item.Item -> e.descriptionId
        is net.minecraft.world.entity.EntityType<*> -> e.descriptionId
        else -> e.toString()
    }

    fun names(s: ListSetting<*>): List<String> = s.value.map { Component.translatable(descId(it)).string }

    @Suppress("UNCHECKED_CAST")
    fun add(s: ListSetting<*>, e: Any) {
        val l = s as ListSetting<Any>
        l.value = ArrayList(l.value).apply { add(e) }
    }

    @Suppress("UNCHECKED_CAST")
    fun removeEntry(s: ListSetting<*>, e: Any) {
        val l = s as ListSetting<Any>
        l.value = ArrayList(l.value).apply { remove(e) }
    }

    @Suppress("UNCHECKED_CAST")
    fun remove(s: ListSetting<*>, index: Int) {
        val l = s as ListSetting<Any>
        if (index in l.value.indices) l.value = ArrayList(l.value).apply { removeAt(index) }
        Sounds.DISABLE.play()
    }

    /** Tab completion in a field (none yet). */
    fun complete(f: Ui.Field) = false
}
