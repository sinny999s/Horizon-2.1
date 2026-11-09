package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.floor

/** A panel over a [ListMenu] that takes all input while it's open. */
interface MenuPopup {
    fun draw(w: Float, h: Float, mouseX: Float, mouseY: Float)

    fun mouseClicked(event: MouseButtonEvent)

    fun mouseScrolled(by: Float)

    fun keyPressed(event: KeyEvent)

    fun charTyped(text: String)
}

/**
 * A vanilla list screen (worlds, servers) drawn in the client's style. It wraps the vanilla screen it
 * replaces and leaves the work to it: the vanilla screen is initialised, ticked and removed with this one, its list
 * holds the entries, and its buttons (mods' ones included) become this screen's, pressing the vanilla ones; buttons in
 * the top third go in the header, the rest in rows at the bottom, as vanilla grouped them. Entries are selected with a
 * click or the arrow keys and opened with a double click or Enter; other keys and typing go to the vanilla screen. A
 * [popup] over it takes all input while it's open.
 */
abstract class ListMenu<E : Any>(val vanilla: Screen, private val heading: String) : Screen(Component.literal(heading)), MenuScreen {
    protected val mc: Minecraft get() = Minecraft.getInstance()

    private class Linked(val vanilla: AbstractButton, val button: MenuButton)

    private val scratch = GuiRenderState()
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private var header = emptyList<Linked>()
    private var footer = emptyList<List<Linked>>()
    /** The panel open over this screen, if any. */
    protected var popup: MenuPopup? = null
    private var scroll = 0f
    private var scrollTarget = 0f

    // Dragging a row to a new place: the row pressed, where, and whether it has gone far enough to be a drag.
    private var pressed: E? = null
    private var pressY = 0f
    private var grab = 0f
    private var dragging = false
    private var dragY = 0f

    /** Each row's drawn place in the list (by [key]), easing to where it belongs as rows move. */
    private val placed = HashMap<Any, Float>()

    // The list's area in the last frame.
    private var listX = 0f
    private var listY = 0f
    private var listW = 0f
    private var listH = 0f

    protected abstract fun entries(): List<E>

    protected abstract fun selected(): E?

    protected abstract fun select(entry: E)

    protected abstract fun selectable(entry: E): Boolean

    protected abstract fun rowHeight(entry: E): Float

    protected abstract fun drawRow(entry: E, x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean)

    protected abstract fun open(entry: E)

    /** Whether a vanilla button gets a button here (false for one this screen does its own way). */
    protected open fun keep(button: AbstractButton) = true

    /** Header buttons of the screen's own, left of vanilla's. */
    protected open fun extraHeader(): List<MenuButton> = emptyList()

    protected open fun rowRightClicked(entry: E) {}

    /** Whether [entry] can be dragged to a new place; those entries come first in the list, one after another. */
    protected open fun reorderable(entry: E) = false

    /** [entry] was dropped at place [to] among the reorderable entries; it was at [from]. */
    protected open fun moved(entry: E, from: Int, to: Int) {}

    /** What stays the same for an entry when the list is rebuilt, so its row eases from its old place. */
    protected open fun key(entry: E): Any = entry

    /** Whether a row other than the one being dragged lies under it (its vanilla text would draw over it). */
    protected fun underDrag(entry: E, y: Float, h: Float): Boolean {
        val drag = pressed?.takeIf { dragging } ?: return false
        if (entry === drag) return false
        val top = dragTop(drag)
        return y < top + rowHeight(drag) && y + h > top
    }

    /** Grey text after the heading (a count...). */
    protected open fun subtitle(): String = ""

    /** Draws header widgets of the screen's own, right-aligned to [right]. */
    protected open fun drawHeaderExtra(right: Float, y: Float, mouseX: Float, mouseY: Float) {}

    protected open fun headerExtraClicked(x: Float, y: Float): Boolean = false

    /** A GUI frame that is never drawn, for vanilla code that only does its work while drawing (loading, pinging). */
    protected fun scratchGraphics(): GuiGraphicsExtractor {
        scratch.reset()
        return GuiGraphicsExtractor(mc, scratch, -1, -1)
    }

    /** Each frame before the rows are drawn. */
    protected open fun beforeRows() {}

    override fun init() {
        vanilla.init(width, height)
        val buttons = vanilla.children().filterIsInstance<AbstractButton>().filter { it.visible && it.message.string.isNotBlank() && keep(it) }
        fun link(b: AbstractButton) = Linked(b, MenuButton(b.message.string, Menus.soundFor(b)) { e -> b.onPress(e) })
        header = buttons.filter { it.y < height / 3 }.sortedBy { it.x }.map(::link)
        footer = buttons.filter { it.y >= height / 3 }.groupBy { it.y }.toSortedMap().values.map { row -> row.sortedBy { it.x }.map(::link) }
    }

    override fun tick() = vanilla.tick()

    // 26.3 (SDL) only delivers typed characters while a screen has text input on.
    override fun added() {
        mc.textInputManager().startTextInput(this)
        enter.reset(0f)
        placed.clear()
        pressed = null
        dragging = false
    }

    override fun removed() {
        popup = null
        mc.textInputManager().stopTextInput(this)
        vanilla.removed()
    }

    override fun onClose() = vanilla.onClose()

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    private fun sync(l: Linked) {
        l.button.label = l.vanilla.message.string
        l.button.active = l.vanilla.active
    }

    override fun draw(mouseX: Float, mouseY: Float) {
        val open = popup
        // Eases in each time it's shown: fading, and rising the last few pixels.
        enter.update(1f)
        val k = enter.value
        Canvas.alpha = k
        Canvas.pose.pushMatrix()
        Canvas.pose.translate(0f, (1f - k) * 8f)
        // Under a popup nothing here is hovered.
        layout(if (open != null) -1f else mouseX, if (open != null) -1f else mouseY)
        Canvas.pose.popMatrix()
        Canvas.alpha = 1f
        open?.draw(width.toFloat(), height.toFloat(), mouseX, mouseY)
    }

    private fun layout(mouseX: Float, mouseY: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(400f, w - 40f)
        val x0 = floor((w - pw) / 2f)
        val top = 14f

        Fonts.semibold12.draw(heading, x0, top, Theme.text)
        val sub = subtitle()
        if (sub.isNotEmpty()) Fonts.medium75.draw(sub, x0 + Fonts.semibold12.width(heading) + 7f, top + 3.5f, Menus.GREY)
        var right = x0 + pw
        for (l in header.asReversed()) {
            sync(l)
            val bw = Fonts.medium75.width(l.button.label) + 18f
            l.button.draw(right - bw, top - 2f, bw, 16f, mouseX, mouseY, Fonts.medium75)
            right -= bw + 5f
        }
        for (b in extraHeader()) {
            val bw = Fonts.medium75.width(b.label) + 18f
            b.draw(right - bw, top - 2f, bw, 16f, mouseX, mouseY, Fonts.medium75)
            right -= bw + 5f
        }
        drawHeaderExtra(right, top - 2f, mouseX, mouseY)

        val rowH = 20f
        val gap = 5f
        val footerTop = h - 12f - footer.size * (rowH + gap) + gap
        var fy = footerTop
        for (row in footer) {
            val total = row.sumOf { it.vanilla.width }.toFloat()
            val avail = pw - gap * (row.size - 1)
            var fx = x0
            for (l in row) {
                sync(l)
                val bw = avail * l.vanilla.width / total
                l.button.draw(fx, fy, bw, rowH, mouseX, mouseY)
                fx += bw + gap
            }
            fy += rowH + gap
        }

        listX = x0
        listY = top + 24f
        listW = pw
        listH = footerTop - 10f - listY
        drawList(mouseX, mouseY)
    }

    private fun drawList(mouseX: Float, mouseY: Float) {
        val entries = entries()
        var content = -ROW_GAP
        for (e in entries) content += rowHeight(e) + ROW_GAP
        val max = maxOf(0f, content - listH)
        scrollTarget = scrollTarget.coerceIn(0f, max)
        scroll = Fps.smooth(scroll, scrollTarget, 18f)
        if (abs(scroll - scrollTarget) < 0.1f) scroll = scrollTarget
        beforeRows()

        // While a row is dragged the list scrolls when it's held near either end, and the others make room for it
        // where it would land.
        val drag = pressed?.takeIf { dragging && it in entries }
        var order = entries
        if (drag != null) {
            val edge = 28f
            if (dragY < listY + edge) scrollTarget -= (listY + edge - dragY) * 8f / Fps.value
            if (dragY > listY + listH - edge) scrollTarget += (dragY - (listY + listH - edge)) * 8f / Fps.value
            scrollTarget = scrollTarget.coerceIn(0f, max)
            order = entries.toMutableList().apply {
                remove(drag)
                add(dropIndex(entries, drag), drag)
            }
        }
        var at = 0f
        for (e in order) {
            placed[key(e)] = Fps.smooth(placed[key(e)] ?: at, at, 22f).let { if (abs(it - at) < 0.1f) at else it }
            at += rowHeight(e) + ROW_GAP
        }

        // Canvas clips the panes, vanilla's scissor any vanilla text the rows submit (MOTDs).
        Canvas.pushScissor(listX - 2f, listY, listX + listW + 2f, listY + listH)
        Canvas.graphics?.enableScissor(listX.toInt() - 2, listY.toInt(), (listX + listW).toInt() + 2, (listY + listH).toInt())
        val inList = drag == null && mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH
        val sel = selected()
        for (e in entries) {
            if (e === drag) continue
            val rh = rowHeight(e)
            val y = rowTop(e)
            if (y + rh >= listY && y <= listY + listH) {
                val hovered = inList && selectable(e) && mouseY >= y && mouseY <= y + rh
                drawRow(e, listX, y, listW, rh, hovered, e === sel)
            }
        }
        // The dragged row last, over the rest, following the cursor.
        if (drag != null) drawRow(drag, listX, dragTop(drag), listW, rowHeight(drag), true, true)
        Canvas.graphics?.disableScissor()
        Canvas.popScissor()

        if (max > 0f) {
            val barH = maxOf(16f, listH * listH / content)
            Menus.rect(listX + listW + 5f, listY + (listH - barH) * (scroll / max), 2f, barH, 1f, 0x50FFFFFF)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        popup?.let {
            it.mouseClicked(event)
            return true
        }
        val right = event.button() == Keys.MOUSE_RIGHT
        if (event.button() != Keys.MOUSE_LEFT && !right) return false
        if (right) return rowAt(event.y().toFloat(), event.x().toFloat())?.let { rowRightClicked(it); true } ?: false
        for (l in header) if (l.button.click(event)) return true
        for (b in extraHeader()) if (b.click(event)) return true
        for (row in footer) for (l in row) if (l.button.click(event)) return true
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        if (headerExtraClicked(x, y)) return true
        val e = rowAt(y, x) ?: return false
        if (doubleClick && selected() === e) {
            Menus.open()
            open(e)
        } else {
            select(e)
            if (reorderable(e)) {
                pressed = e
                pressY = y
                grab = y - rowTop(e)
                dragY = y
            }
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (popup != null || pressed == null) return false
        val y = event.y().toFloat()
        if (!dragging && abs(y - pressY) > 3f) dragging = true
        dragY = y
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val drag = pressed
        pressed = null
        if (drag == null || !dragging) return false
        dragging = false
        val entries = entries()
        if (drag !in entries) return true
        // It eases into its new place from where it was let go.
        placed[key(drag)] = dragTop(drag) - (listY - scroll)
        val from = entries.filter(::reorderable).indexOf(drag)
        val to = dropIndex(entries, drag)
        if (from != to) {
            moved(drag, from, to)
            Menus.step(to > from)
        }
        return true
    }

    /** Where the dragged row's top is: following the cursor, kept inside the list. */
    private fun dragTop(drag: E) = (dragY - grab).coerceIn(listY, maxOf(listY, listY + listH - rowHeight(drag)))

    /** A row's top on screen, where it's drawn now. */
    private fun rowTop(e: E) = listY - scroll + (placed[key(e)] ?: 0f)

    /** Where [drag] would land among the reorderable entries: below each other one whose place its top is past halfway. */
    private fun dropIndex(entries: List<E>, drag: E): Int {
        val top = dragTop(drag)
        var y = listY - scroll
        var i = 0
        for (e in entries) {
            if (!reorderable(e)) break
            if (e === drag) continue
            val rh = rowHeight(e)
            if (top >= y + (rh + ROW_GAP) / 2f) i++
            y += rh + ROW_GAP
        }
        return i
    }

    /** The selectable entry under ([x], [y]), if any. */
    private fun rowAt(y: Float, x: Float): E? {
        if (x < listX || x > listX + listW || y < listY || y > listY + listH) return null
        var ry = listY - scroll
        for (e in entries()) {
            val rh = rowHeight(e)
            if (y >= ry && y <= ry + rh) return e.takeIf(::selectable)
            ry += rh + ROW_GAP
        }
        return null
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        popup?.let {
            it.mouseScrolled(scrollY.toFloat())
            return true
        }
        scrollTarget -= scrollY.toFloat() * 28f
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        popup?.let {
            it.keyPressed(event)
            return true
        }
        val sel = selected()
        when (event.key()) {
            Keys.ENTER, InputConstants.KEY_NUMPADENTER -> if (sel != null) {
                Menus.open()
                open(sel)
                return true
            }
            Keys.UP, Keys.DOWN -> {
                move(if (event.key() == Keys.UP) -1 else 1)
                return true
            }
            Keys.ESCAPE -> Menus.back()
        }
        return vanilla.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val p = popup ?: return vanilla.charTyped(event)
        p.charTyped(event.codepointAsString())
        return true
    }

    /** Selects the [by]th selectable entry from the selected one and scrolls it into view. */
    private fun move(by: Int) {
        val entries = entries()
        val options = entries.filter(::selectable)
        if (options.isEmpty()) return
        val i = options.indexOfFirst { it === selected() }
        val next = options[if (i < 0) 0 else (i + by).coerceIn(0, options.size - 1)]
        select(next)
        var y = 0f
        for (e in entries) {
            if (e === next) break
            y += rowHeight(e) + ROW_GAP
        }
        if (y < scrollTarget) scrollTarget = y
        if (y + rowHeight(next) > scrollTarget + listH) scrollTarget = y + rowHeight(next) - listH
    }

    private companion object {
        const val ROW_GAP = 4f
    }
}
