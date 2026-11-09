package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.floor

/**
 * A simple vanilla screen out of a world (the multiplayer warning, Credits and Attribution) in the
 * menus' style, over the backdrop: a panel with its title, its text wrapped, its checkboxes and its buttons in vanilla's
 * rows, each pressing vanilla's own (so what they do, and Esc, stay vanilla's).
 */
class PanelMenu(val vanilla: Screen) : Screen(vanilla.title), MenuScreen {
    private class Linked(val vanilla: AbstractButton, val button: MenuButton)

    private var texts = emptyList<String>()
    private var checks = emptyList<Checkbox>()
    private var rows = emptyList<List<Linked>>()
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private val checkHover = HashMap<Checkbox, Animation>()

    override fun init() {
        vanilla.init(width, height)
        val widgets = vanilla.children().filterIsInstance<AbstractWidget>().filter { it.visible }
        val title = vanilla.title.string
        texts = widgets.filter { it !is AbstractButton }.map { it.message.string }.filter { it.isNotBlank() && it != title }
        checks = widgets.filterIsInstance<Checkbox>()
        val buttons = widgets.filterIsInstance<AbstractButton>().filter { it !is Checkbox && it.message.string.isNotBlank() }
        rows = buttons.groupBy { it.y }.toSortedMap().values.map { row ->
            row.sortedBy { it.x }.map { b -> Linked(b, MenuButton(b.message.string, Menus.soundFor(b)) { e -> b.onPress(e) }) }
        }
    }

    override fun added() = enter.reset(0f)

    override fun tick() = vanilla.tick()

    override fun removed() = vanilla.removed()

    override fun onClose() = vanilla.onClose()

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    /** [text] broken into lines no wider than [w] (vanilla's own line breaks kept). */
    private fun wrap(text: String, w: Float): List<String> {
        val font = Fonts.medium75
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (font.width(next) <= w || line.isEmpty()) line = next
                else {
                    out += line
                    line = word
                }
            }
            out += line
        }
        return out
    }

    override fun draw(mouseX: Float, mouseY: Float) {
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(if (texts.isEmpty()) 240f else 320f, w - 40f)
        val inner = pw - 24f
        val lines = texts.flatMap { wrap(it, inner) + "" }.dropLast(1)
        val bh = 20f
        val gap = 5f
        val lineH = 10f
        val textH = if (lines.isEmpty()) 0f else lines.size * lineH + 8f
        val checkH = checks.size * 16f + if (checks.isEmpty()) 0f else 4f
        val ph = 36f + textH + checkH + rows.size * (bh + gap) + 7f
        val x = floor((w - pw) / 2f)
        val y = floor(((h - ph) / 2f).coerceAtLeast(8f) + (1f - k) * 10f)
        Canvas.alpha = k
        Menus.glass(x, y, pw, ph, 8f, 0xFF3A3A3A.toInt(), 0x40FFFFFF)
        Fonts.semibold12.draw(Menus.fit(title.string, Fonts.semibold12, inner), x + 12f, y + 11f, Theme.text)
        var ty = y + 34f
        for (l in lines) {
            Fonts.medium75.draw(l, x + 12f, ty, 0xFFB8B8B8.toInt())
            ty += lineH
        }
        if (lines.isNotEmpty()) ty += 8f
        for (c in checks) {
            checkbox(c, x + 12f, ty, inner, mouseX, mouseY)
            ty += 16f
        }
        if (checks.isNotEmpty()) ty += 4f
        for (row in rows) {
            val total = row.sumOf { it.vanilla.width }.toFloat()
            val avail = inner - gap * (row.size - 1)
            var rx = x + 12f
            for (l in row) {
                l.button.label = l.vanilla.message.string
                l.button.active = l.vanilla.active
                val bw = avail * l.vanilla.width / total
                l.button.draw(rx, ty, bw, bh, mouseX, mouseY)
                rx += bw + gap
            }
            ty += bh + gap
        }
        Canvas.alpha = 1f
    }

    private val checkRects = HashMap<Checkbox, FloatArray>()

    /** A checkbox as a small box (filled light with a dark tick when on) and its label; the row is clickable. */
    private fun checkbox(c: Checkbox, x: Float, y: Float, w: Float, mouseX: Float, mouseY: Float) {
        val font = Fonts.medium75
        val label = Menus.fit(c.message.string, font, w - 18f)
        val rw = 16f + font.width(label)
        checkRects[c] = floatArrayOf(x, y, rw, 12f)
        val over = mouseX >= x && mouseX <= x + rw && mouseY >= y && mouseY <= y + 12f
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        val a = checkHover.getOrPut(c) { Animation(Easing.OUT_CUBIC, 140) }
        a.update(if (over) 1f else 0f)
        val on = c.selected()
        // Ticked, a solid light box (a glass pane barely lightens over the dark backdrop).
        if (on) Menus.rect(x, y, 12f, 12f, 3f, Menus.FILL)
        else Menus.glass(x, y, 12f, 12f, 3f, Colors.lerp(0xFF3A3A3A.toInt(), 0xFF5A5A5A.toInt(), a.value), Colors.lerp(0x40FFFFFF, Menus.OUTLINE, a.value))
        if (on) Ui.icon("check", x + 2f, y + 2f, 8f, 0xFF1C1C20.toInt())
        font.drawMiddle(label, x + 17f, y + 6f, Colors.lerp(0xFFC8C8C8.toInt(), Theme.text, a.value))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != Keys.MOUSE_LEFT) return false
        for (row in rows) for (l in row) if (l.button.click(event)) return true
        val mx = event.x().toFloat()
        val my = event.y().toFloat()
        for (c in checks) {
            val r = checkRects[c] ?: continue
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                Menus.toggle(!c.selected())
                c.onPress(event)
                return true
            }
        }
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == Keys.ESCAPE) Menus.back()
        return vanilla.keyPressed(event)
    }
}
