package dev.horizon.client.hud

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.config.SettingsHolder
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Icons
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.ModernHudEditor
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import dev.horizon.client.render.VectorIcon
import dev.horizon.client.render.WebText
import dev.horizon.client.setting.BoolSetting
import dev.horizon.client.setting.Setting
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A widget's top-left corner as fractions of the GUI-scaled screen. Mutated in place. */
@Serializable
data class Position(var x: Float = 0.5f, var y: Float = 0.5f)

class PositionSetting(id: String, default: Position) : Setting<Position>(id, default) {
    override fun parse(s: String) {
        value = JSON.decodeFromString(Position.serializer(), s)
    }

    override fun save(): String = JSON.encodeToString(Position.serializer(), value)
}

/**
 * A HUD widget: settings `enabled` and `position`, then the widget's own. Every frame it draws, then
 * sticks to its neighbours (overlap push, 2 units right of a same-row widget within 10, 2 below an aligned one within
 * 10) and hugs the right margin when its right edge is within 55 of the screen edge. In chat a left drag moves it (with
 * white left/right/top guides), any other button opens a popup of its boolean settings.
 * The new HUD editor snaps within 3 units and draws its own guides and settings, and the drawing helpers
 * below draw the new look (design/clickgui/hud.css), in CSS px / 2.
 */
abstract class HudWidget(override val id: String) : SettingsHolder {
    override val settings = ArrayList<Setting<*>>()
    private val enabledSetting = bool("enabled", false)
    private val positionSetting = add(PositionSetting("position", Position()))
    protected val mc: Minecraft get() = Minecraft.getInstance()

    /** `assets/horizon/vector/<id>.svg`, 10x10, drawn in the theme colour as a CSS mask. */
    val vicon by lazy { VectorIcon(if (id == "armor-widget") "armor" else id, 10f, mask = true) }

    /** Each id part capitalised and followed by a space, the last one too (on purpose). */
    val name: String = id.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } + " " }

    var enabled: Boolean
        get() = enabledSetting.value
        set(v) {
            enabledSetting.value = v
        }

    val pos: Position get() = positionSetting.value

    /** Size set while drawing. */
    var width = 0f
    var height = 0f

    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f
    private var popupOpen = false
    private var popupX = 0f
    private var popupY = 0f

    protected val screenW: Int get() = mc.window.guiScaledWidth
    protected val screenH: Int get() = mc.window.guiScaledHeight

    /** Left edge in GUI units. */
    val left: Float get() = pos.x * screenW

    /** Top edge in GUI units. */
    val top: Float get() = pos.y * screenH

    /** Chat or the new HUD editor is open. */
    protected val editorMode: Boolean get() = mc.gui.screen().let { it is ChatScreen || it === ModernHudEditor }

    /** Being dragged. */
    val isDragged: Boolean get() = dragging

    protected fun <S : Setting<*>> add(s: S): S = s.also { settings += it }

    protected fun bool(id: String, default: Boolean) = add(BoolSetting(id, default))

    protected fun size(w: Float, h: Float) {
        width = w
        height = h
    }

    fun hovered(): Boolean = left <= Hud.mouseX && Hud.mouseX <= left + width && top <= Hud.mouseY && Hud.mouseY <= top + height

    /** Draw, and set the size. */
    abstract fun render()

    /** After the plates, dividers and icons of every widget are drawn. */
    open fun postRender() {}

    /** Every player tick. */
    open fun tick() {}

    /** A left press grabbed this widget. */
    protected open fun grabbed() {}

    private fun clamp(v: Float, lo: Float, hi: Float) = if (lo > hi) lo else v.coerceIn(lo, hi)

    /** One frame. */
    fun frame() {
        render()
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        for (o in Hud.widgets) {
            if (o === this || !o.enabled) continue
            if (o.left <= left && left <= o.left + o.width && o.top <= top && top <= o.top + o.height) {
                val fx = (o.left + o.width + 2f) / w
                val fy = (o.top + o.height + 2f) / h
                val lo = min(5f / h, 1f - height / h)
                val hi = max(5f / h, 1f - height / h)
                if (o.left != left && o.top == top) pos.x = clamp(fx, 5f / w, 1f)
                if (o.top != top && (o.left == left || o.left + o.width == left + width)) pos.y = clamp(fy, lo, hi)
            }
            if (o.top == top && abs(o.left + o.width - left) < 10f) pos.x = clamp((o.left + o.width + 2f) / w, 5f / w, 1f)
            if ((o.left == left || o.left + o.width == left + width) && abs(o.top + o.height - top) < 10f) {
                pos.y = clamp((o.top + o.height + 2f) / h, min(5f / h, 1f - height / h), max(5f / h, 1f - height / h))
            }
        }
        val right = left + width
        if (right > w - 5f || abs(right - w - 5f) < 60f) pos.x = clamp((w - width - 5f) / w, 5f / w, 1f)

        val newEditor = mc.gui.screen() === ModernHudEditor
        if (editorMode) {
            if (dragging) {
                pos.x = clamp((Hud.mouseX - grabX) / w, 5f / w, 1f - (width + 5f) / w)
                pos.y = clamp((Hud.mouseY - grabY) / h, min(5f / h, 1f - height / h), max(5f / h, 1f - height / h))
                val near = if (newEditor) 3f else 2f
                var snapX = 0f
                var guideX = 0f
                var guideY = 0f
                for (o in Hud.widgets) {
                    if (o === this || !o.enabled) continue
                    if (abs(o.left + o.width - (left + width)) < near) {
                        snapX = o.left + o.width - width
                        guideX = o.left + o.width
                    } else if (abs(o.left - left) < near) {
                        snapX = o.left
                        guideX = snapX
                    }
                    if (abs(o.top - top) < near) guideY = o.top
                    if (guideX != 0f) {
                        if (newEditor) Hud.guides += true to guideX else Canvas.line(guideX, 5f, guideX, h - 5f, -1)
                        pos.x = snapX / w
                    }
                    if (guideY != 0f) {
                        if (newEditor) Hud.guides += false to guideY else Canvas.line(5f, guideY, w - 5f, guideY, -1)
                        pos.y = guideY / h
                    }
                }
            }
            if (popupOpen) renderPopup()
        }
        if (hovered() && mc.gui.screen() is ChatScreen) {
            Cursor.request(if (Hud.dragged != null) CursorTypes.RESIZE_ALL else CursorTypes.POINTING_HAND)
            Hud.cursorOwner = this
        }
        if (newEditor && width > 0f && (dragging || (hovered() && Hud.dragged == null && !ModernHudEditor.overUi()))) {
            Cursor.request(if (dragging) CursorTypes.RESIZE_ALL else CursorTypes.POINTING_HAND)
            Hud.cursorOwner = this
        }
    }

    private fun popupSettings() = settings.filter { it.id != "enabled" && it.id != "position" }

    private fun popupWidth(list: List<Setting<*>>) = (list.maxOfOrNull { Fonts.medium8.width(it.displayName) } ?: 0f) + 23f

    /** The settings popup: every setting gets a box, only booleans can be toggled. Drawn under every plate. */
    private fun renderPopup() {
        val list = popupSettings()
        val w = popupWidth(list)
        Canvas.glass(popupX, popupY, w, 14f * list.size + 6f, 3f, 1.5f, 0f, 1f, Theme.widgetBlur, Theme.outline)
        var off = 0f
        for (s in list) {
            Fonts.medium8.draw(s.displayName, popupX + 3f, popupY + off + 3f, Theme.text)
            Canvas.glass(popupX + w - 14f, popupY + 3.5f + off, 11f, 11f, 3f, 2.5f, 0f, 1f, Theme.module, Theme.moduleOutline)
            if (s.value == true) Icons.CHECK.draw(popupX + w - 14.5f, popupY + off + 2f, primary = Theme.primary)
            off += 14f
        }
    }

    /** Every mouse button event while in a world, before the screen sees it. */
    fun mouse(button: Int, press: Boolean) {
        val newEditor = mc.gui.screen() === ModernHudEditor
        // The new editor's panel, top bar or popover is under the mouse.
        if (newEditor && press && ModernHudEditor.overUi()) return
        val mx = Hud.mouseX
        val my = Hud.mouseY
        if (button == Keys.MOUSE_LEFT) {
            if (popupOpen && press) {
                val list = popupSettings()
                val w = popupWidth(list)
                var off = 0f
                for (s in list) {
                    if (mx >= popupX + w - 14f && mx <= popupX + w - 3f && my >= popupY + 3.5f + off && my <= popupY + 14.5f + off) {
                        if (s is BoolSetting) s.value = !s.value
                    }
                    off += 14f
                }
                return
            }
            if (!press) {
                dragging = false
                Hud.dragged = null
                return
            }
            if (!hovered()) return
            if (Hud.dragged != null) return
            grabbed()
            grabX = mx - left
            grabY = my - top
            dragging = true
            Hud.dragged = this
            return
        }
        // The new editor opens its own settings (ModernHudEditor.mouseClicked).
        if (newEditor) return
        if (press && hovered() && popupSettings().isNotEmpty()) {
            popupOpen = !popupOpen
            popupX = mx
            popupY = my
        }
    }

    // ---- drawing helpers ----

    /** A .pl plate ([radius] 3.5 unless a panel's); batched ones are drawn after every widget's [render]. */
    protected fun plate(x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f, batched: Boolean = true, radius: Float = 3.5f) {
        if (batched) Hud.plates += Hud.Plate(Matrix3x2f(Canvas.pose), x, y, w, h, alpha, radius)
        else Hud.plate(x, y, w, h, radius, alpha)
    }

    /** A 0.5x8.5 divider, always white at alpha 68, drawn after the plates. */
    protected fun divider(x: Float, y: Float, alpha: Float = 1f) {
        Hud.dividers += Hud.Divider(Matrix3x2f(Canvas.pose), x, y, alpha)
    }

    /** This widget's [vicon] in the theme colour, drawn after the dividers. */
    protected fun icon(x: Float, y: Float, alpha: Float = 1f) {
        Hud.icons += Hud.IconDraw(Matrix3x2f(Canvas.pose), x, y, alpha, vicon)
    }

    /** The one-line widget (icon, divider, text). */
    protected fun simple(s: String) {
        // .pl: 3.5 padding and a hairline, the 10-unit icon, 3 gap, the divider, 3 gap, the text
        val pw = tw(s) + 24.5f
        plate(left, top, pw, 13f)
        icon(left + 4f, top + 1.5f)
        divider(left + 17f, top + 2.25f)
        ctext(s, left + 20.5f, top + 6.5f, Tok.T1)
        size(pw, 13f)
    }

    /** A list header plate, scaled about its centre. */
    protected fun header(title: String, scale: Float, alignRight: Boolean) {
        val w = headerWidth(title)
        val hx = left + if (alignRight) width - w else 0f
        val s = scale.coerceIn(0f, 2f)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(hx + w / 2, top + 8)
        pose.scale(s, s)
        pose.translate(-(hx + w / 2), -(top + 8))
        // .pl.head: 16 tall, 3 in on the left and 4.5 on the right
        plate(hx, top, w, 16f)
        icon(hx + 3.5f, top + 3f)
        divider(hx + 16.5f, top + 3.75f)
        ctext(title, hx + 20f, top + 8f, Tok.T1)
        pose.popMatrix()
    }

    // ---- the new look's helpers ----

    /** [s]'s width in the new look's text (or [font]). */
    protected fun tw(s: String, font: WebText = HudType.text) = font.width(s)

    /** The new look's text, queued: from [x], its capitals centred on [cy]; [right] makes it a gradient. */
    protected fun ctext(s: String, x: Float, cy: Float, color: Int, font: WebText = HudType.text, right: Int = color) {
        if (s.isEmpty()) return
        Hud.texts += Hud.TextDraw(Matrix3x2f(Canvas.pose), font, s, x, cy, color, right)
    }

    /** Vanilla's colours for the codes 0-9 and a-f. */
    private val CODES = intArrayOf(0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(), 0xFFAA0000.toInt(),
        0xFFAA00AA.toInt(), 0xFFFFAA00.toInt(), 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(),
        0xFF55FFFF.toInt(), 0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), -1)

    /** A list header's width in the new look. */
    protected fun headerWidth(title: String) = tw(title) + 25f

    /** A text plate's width in the new look: the text 4 in from each side. */
    protected fun cellWidth(s: String) = tw(s) + 8f

    /** A text plate in the new look ([alpha] while a row slides); returns its width. */
    protected fun cell(s: String, x: Float, y: Float, color: Int = Tok.T1, alpha: Float = 1f): Float {
        val w = cellWidth(s)
        plate(x, y, w, 13f, alpha)
        ctext(s, x + 4f, y + 6.5f, Colors.withAlpha(color, Colors.alphaF(color) * alpha))
        return w
    }

    /**
     * [s] with its § colour codes (a team's prefix, a status), queued from [x] in [base] where none is set; returns its
     * width, and only measures with [draw] off.
     */
    protected fun colored(s: String, x: Float, cy: Float, base: Int, alpha: Float = 1f, draw: Boolean = true): Float {
        var cx = x
        var color = base
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val t = run.toString()
            if (draw) ctext(t, cx, cy, Colors.withAlpha(color, Colors.alphaF(color) * alpha))
            cx += tw(t)
            run.clear()
        }
        var i = 0
        while (i < s.length) {
            if (s[i] == '\u00a7' && i + 1 < s.length) {
                flush()
                val code = s[i + 1].lowercaseChar()
                val n = "0123456789abcdef".indexOf(code)
                if (code == 'r') color = base else if (n >= 0) color = CODES[n]
                i += 2
                continue
            }
            run.append(s[i])
            i++
        }
        flush()
        return cx - x
    }
}
