package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import kotlin.math.abs
import kotlin.math.floor

/**
 * Not in the original: vanilla's Add / Edit Server screen and its Direct Connection screen in the menus' style, over the
 * backdrop: a panel with the screen's title, each text field under a small grey label, the Server Resource Packs choice
 * as a button that cycles it, and Cancel beside Done. The fields are vanilla's own [EditBox]es, only drawn here: keys and
 * typing go to the vanilla screen (so Tab, Enter, paste, the length limit and Done's state stay vanilla's), and a click
 * or drag in a field focuses it and puts the caret (or the selection) where it lands in the client's font.
 */
class ServerEditMenu(val vanilla: Screen) : Screen(vanilla.title), MenuScreen {
    private class Linked(val vanilla: AbstractButton, val button: MenuButton)

    private class Field(val box: EditBox, val label: String) {
        var x = 0f
        var y = 0f
        var w = 0f
        /** The first character shown, so the caret stays in view. */
        var offset = 0
        val focus = Animation(Easing.OUT_CUBIC, 160)
        val hover = Animation(Easing.OUT_CUBIC, 160)

        fun contains(mx: Float, my: Float) = mx >= x && mx <= x + w && my >= y && my <= y + FIELD_H
    }

    private var fields = emptyList<Field>()
    private var cycles = emptyList<Linked>()
    private var bottom = emptyList<Linked>()
    private var started = false
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private var dragging: Field? = null
    /** When the caret last moved or the text changed: it stays lit for a moment after. */
    private var lastInput = 0L

    override fun init() {
        // Once built, vanilla's own resize keeps what was typed (building it again would reset its fields).
        if (started) vanilla.resize(width, height) else vanilla.init(width, height)
        started = true
        val widgets = vanilla.children()
        fields = widgets.filterIsInstance<EditBox>().filter { it.isVisible }.map { Field(it, it.message.string) }
        fun link(b: AbstractButton) = Linked(b, MenuButton(b.message.string, Menus.soundFor(b)) { e -> b.onPress(e) })
        val buttons = widgets.filterIsInstance<AbstractButton>().filter { it.visible && it.message.string.isNotBlank() }
        cycles = buttons.filterIsInstance<CycleButton<*>>().map(::link)
        // Cancel first, as in the client's own dialogs, then the action.
        bottom = (buttons - cycles.map { it.vanilla }.toSet())
            .sortedBy { if ((it.message.contents as? TranslatableContents)?.key == "gui.cancel") 0 else 1 }.map(::link)
    }

    override fun added() = enter.reset(0f)

    override fun tick() = vanilla.tick()

    override fun removed() = vanilla.removed()

    override fun onClose() = vanilla.onClose()

    override fun shouldCloseOnEsc() = vanilla.shouldCloseOnEsc()

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun draw(mouseX: Float, mouseY: Float) {
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(260f, w - 40f)
        val inner = pw - 24f
        val gap = 5f
        val ph = 34f + fields.size * (LABEL_H + FIELD_H + 8f) + cycles.size * (BUTTON_H + gap) + 6f + BUTTON_H + 12f
        val x = floor((w - pw) / 2f)
        val y = floor(((h - ph) / 2f).coerceAtLeast(8f) + (1f - k) * 10f)
        Canvas.alpha = k
        Menus.glass(x, y, pw, ph, 8f, 0xFF3A3A3A.toInt(), 0x40FFFFFF)
        Fonts.semibold12.draw(Menus.fit(title.string, Fonts.semibold12, inner), x + 12f, y + 11f, Theme.text)
        var ty = y + 34f
        for (f in fields) {
            Fonts.medium65.draw(f.label, x + 13f, ty, Menus.GREY)
            ty += LABEL_H
            f.x = x + 12f
            f.y = ty
            f.w = inner
            field(f, mouseX, mouseY)
            ty += FIELD_H + 8f
        }
        for (l in cycles) {
            button(l, x + 12f, ty, inner, mouseX, mouseY)
            ty += BUTTON_H + gap
        }
        ty += 6f
        var bx = x + 12f
        val bw = (inner - gap * (bottom.size - 1)) / bottom.size.coerceAtLeast(1)
        for (l in bottom) {
            button(l, bx, ty, bw, mouseX, mouseY)
            bx += bw + gap
        }
        Canvas.alpha = 1f
    }

    /** A vanilla button as a [MenuButton]: its current message, its state, and lit while Tab has it focused. */
    private fun button(l: Linked, x: Float, y: Float, w: Float, mouseX: Float, mouseY: Float) {
        l.button.label = l.vanilla.message.string
        l.button.active = l.vanilla.active
        val keyboard = l.vanilla.isFocused && fields.none { it.box.isFocused }
        l.button.draw(x, y, w, BUTTON_H, if (keyboard) x + w / 2f else mouseX, if (keyboard) y + BUTTON_H / 2f else mouseY)
    }

    private fun field(f: Field, mouseX: Float, mouseY: Float) {
        val box = f.box
        val font = Fonts.medium75
        val focused = box.isFocused
        val over = f.contains(mouseX, mouseY)
        if (over) Cursor.request(CursorTypes.IBEAM)
        f.focus.update(if (focused) 1f else 0f)
        f.hover.update(if (over) 1f else 0f)
        val lit = maxOf(f.focus.value, f.hover.value * 0.45f)
        Menus.glass(f.x, f.y, f.w, FIELD_H, 5f, Colors.lerp(0xFF2C2C2C.toInt(), 0xFF343434.toInt(), f.hover.value),
            Colors.lerp(0x30FFFFFF, Menus.OUTLINE, lit))
        val tx = f.x + PAD
        val cy = f.y + FIELD_H / 2f
        val room = f.w - PAD * 2f
        val value = box.value
        if (value.isEmpty()) {
            val hint = hint(box)
            if (hint.isNotEmpty()) font.drawMiddle(Menus.fit(hint, font, room), tx, cy, 0xFF6E6E6E.toInt())
        }
        val cursor = box.cursorPosition.coerceIn(0, value.length)
        scroll(f, value, cursor, room)
        val end = visibleEnd(f, value, room)
        val shown = value.substring(f.offset, end)
        // The selection, behind the text, cut to what's shown.
        val sel = highlight(box).coerceIn(0, value.length)
        if (focused && sel != cursor) {
            val a = minOf(sel, cursor).coerceIn(f.offset, end)
            val b = maxOf(sel, cursor).coerceIn(f.offset, end)
            if (b > a) {
                val sx = tx + font.width(value.substring(f.offset, a))
                Menus.rect(sx, cy - 5f, font.width(value.substring(a, b)), 10f, 1.5f, 0x55FFFFFF)
            }
        }
        if (shown.isNotEmpty()) font.drawMiddle(shown, tx, cy, Theme.text)
        val since = System.currentTimeMillis() - lastInput
        if (focused && (since < 500 || since / 530 % 2 == 0L)) {
            val cx = tx + font.width(value.substring(f.offset, cursor.coerceIn(f.offset, end)))
            Menus.rect(cx, cy - 4.5f, 1f, 9f, 0f, 0xE6FFFFFF.toInt())
        }
    }

    /** Keeps [f]'s caret in view: scrolls left to it, right until it fits, and back while the end leaves room. */
    private fun scroll(f: Field, value: String, cursor: Int, room: Float) {
        val font = Fonts.medium75
        f.offset = f.offset.coerceIn(0, value.length)
        if (cursor < f.offset) f.offset = cursor
        while (f.offset < cursor && font.width(value.substring(f.offset, cursor)) > room - 1f) f.offset++
        while (f.offset > 0 && font.width(value.substring(f.offset - 1)) <= room - 1f) f.offset--
    }

    /** The end of the text that fits in [f] from its offset. */
    private fun visibleEnd(f: Field, value: String, room: Float): Int {
        var end = value.length
        while (end > f.offset && Fonts.medium75.width(value.substring(f.offset, end)) > room) end--
        return end
    }

    /** The place in [f]'s text nearest window x [mx]. */
    private fun indexAt(f: Field, mx: Float): Int {
        val value = f.box.value
        val font = Fonts.medium75
        val end = visibleEnd(f, value, f.w - PAD * 2f)
        val rel = mx - (f.x + PAD)
        var best = f.offset
        var bestD = Float.MAX_VALUE
        for (i in f.offset..end) {
            val d = abs(font.width(value.substring(f.offset, i)) - rel)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != Keys.MOUSE_LEFT) return false
        for (l in cycles + bottom) if (l.button.click(event)) return true
        val mx = event.x().toFloat()
        val my = event.y().toFloat()
        val f = fields.firstOrNull { it.contains(mx, my) } ?: return false
        vanilla.focused = f.box
        val i = indexAt(f, mx)
        if (doubleClick) {
            selectWord(f.box, i)
        } else {
            f.box.moveCursorTo(i, event.hasShiftDown())
            dragging = f
        }
        lastInput = System.currentTimeMillis()
        return true
    }

    /** Selects the word (letters, digits, '-', '_') around [i]. */
    private fun selectWord(box: EditBox, i: Int) {
        val v = box.value
        fun word(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'
        var a = i
        var b = i
        while (a > 0 && word(v[a - 1])) a--
        while (b < v.length && word(v[b])) b++
        box.moveCursorTo(a, false)
        box.moveCursorTo(b, true)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val f = dragging ?: return false
        f.box.moveCursorTo(indexAt(f, event.x().toFloat()), true)
        lastInput = System.currentTimeMillis()
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (event.key() == Keys.ESCAPE) Menus.back()
        return vanilla.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean = vanilla.keyReleased(event)

    override fun charTyped(event: CharacterEvent): Boolean {
        lastInput = System.currentTimeMillis()
        return vanilla.charTyped(event)
    }

    private companion object {
        const val LABEL_H = 11f
        const val FIELD_H = 20f
        const val BUTTON_H = 20f
        const val PAD = 7f

        // MC 26.3 is unobfuscated, so these names hold in the shipped game too.
        private val HIGHLIGHT = EditBox::class.java.getDeclaredField("highlightPos").apply { isAccessible = true }
        private val HINT = EditBox::class.java.getDeclaredField("hint").apply { isAccessible = true }

        /** The other end of [box]'s selection (its caret when nothing is selected). */
        fun highlight(box: EditBox): Int = HIGHLIGHT.getInt(box)

        /** [box]'s grey text for when it's empty. */
        fun hint(box: EditBox): String = (HINT.get(box) as Component?)?.string ?: ""
    }
}
