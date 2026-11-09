package dev.horizon.client.gui.popup

import dev.horizon.client.gui.Icons
import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import kotlin.math.min

/**
 * A popup window: a glass panel centred 50 units above the middle, zooming in and out (200 ms OUT_QUINT),
 * with a close button. It takes all input while open and removes itself when its close animation ends.
 */
abstract class Popup {
    private val zoom = Animation(Easing.OUT_QUINT, 200)
    protected var w = 200f
    protected var h = 250f
    protected var scroll = 0f
    var closing = false

    protected val x get() = Minecraft.getInstance().window.guiScaledWidth / 2f - w / 2f
    protected val y get() = Minecraft.getInstance().window.guiScaledHeight / 2f - h / 2f - 50f

    fun render(mouseX: Int, mouseY: Int) {
        zoom.update(if (!closing) 1f else 0f)
        val x = x
        val y = y
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x + w / 2f, y + h / 2f)
        val s = zoom.value.coerceIn(0f, 2f)
        pose.scale(s, s)
        pose.translate(-(x + w / 2f), -(y + h / 2f))
        Canvas.glass(x, y, w, h, 7f, 2f, 0f, 1f, Theme.blur, Theme.outline)
        Canvas.quad(x + 6, y + 24, w - 12, 0.5f, 0x44FFFFFF)
        val hovered = x + w - 25 <= mouseX && mouseX <= x + w - 5 && y + 5f <= mouseY && mouseY <= y + 25f
        Canvas.glass(x + w - 25, y + 5, 20f, 15f, 4f, 2f, 0f, 1f, if (hovered) Colors.brighter(Theme.blur) else Theme.blur, Theme.outline)
        Icons.BACK.draw(x + w - 19.5f, y + 5.5f, primary = if (hovered) Theme.text else Theme.inactiveText)
        content(mouseX, mouseY)
        pose.popMatrix()
        if (closing && zoom.value <= 0f) {
            if (KitPopup.sub == null) PopupScreen.popup = null else KitPopup.sub = null
        }
    }

    protected abstract fun content(mouseX: Int, mouseY: Int)

    open fun mouseClicked(mx: Int, my: Int, button: Int) {
        if (x + w - 25 <= mx && mx <= x + w - 5 && y + 5 <= my && my <= y + 25) closing = true
    }

    open fun keyPressed(key: Int) {
        if (key == Keys.ESCAPE) closing = true
    }

    open fun charTyped(c: Char) {}

    open fun mouseScrolled(amount: Double) {
        scroll = min(scroll + amount.toFloat() * 10f, 0f)
    }
}

/**
 * A searchable picker: a search field (shows "Search" while unfocused), All/Selected tabs and a list of
 * icon + name rows; a click toggles the row hovered last frame.
 */
abstract class ListPopup<T : Any>(private val title: String) : Popup() {
    private var hovered: T? = null
    private var all = true
    private var focused = false
    private var search = ""

    protected abstract fun entries(): List<T>
    protected abstract fun name(e: T): String
    protected abstract fun selected(e: T): Boolean
    protected abstract fun toggle(e: T)
    protected abstract fun icon(e: T): ItemStack?

    override fun content(mouseX: Int, mouseY: Int) {
        val x = x
        val y = y
        Fonts.semibold12.drawGradientCentered(title, x + w / 2f, y + 3, 10)
        // search field
        Canvas.glass(x + 4f, y + 28f, w - 8f, 20f, 4f, 0f, 0.1f, 1f, Colors.darker(Theme.blur), Theme.outline)
        val text = if (focused) search + (if (System.currentTimeMillis() % 700 > 350) "_" else "") else "Search"
        Fonts.semibold12.draw(text, x + 8f, y + 29.5f, if (focused) Theme.text else Theme.inactiveText)
        // tabs
        Canvas.glass(x + 4f, y + 52f, w - 8f, 17f, 4f, 2f, 0.1f, 1f, Colors.darker(Theme.blur), Theme.outline)
        if (all) Canvas.glass(x + 6f, y + 54f, w / 2f - 8f, 13f, 3f, 2f, 0.1f, 1f, Theme.blur, Theme.outline)
        else Canvas.glass(x + 6f + w / 2f - 4, y + 54f, w / 2f - 8f, 13f, 3f, 2f, 0.1f, 1f, Theme.blur, Theme.outline)
        Fonts.medium95.drawCentered("All", x + w / 4f, y + 60.5f, if (all) Theme.primary else Theme.inactiveText)
        Fonts.medium95.drawCentered("Selected", x + 3 * w / 4f, y + 60.5f, if (!all) Theme.primary else Theme.inactiveText)
        // list
        hovered = null
        Canvas.pushScissor(x, y + 71f, x + w, y + h)
        val rows = entries().filter { (all || selected(it)) && (search.isEmpty() || name(it).contains(search, ignoreCase = true)) }
        rows.forEachIndexed { i, e ->
            val ry = y + 78f + i * 20f + scroll
            if (ry < y + 40 || ry > y + h) return@forEachIndexed
            val hov = x <= mouseX && mouseX <= x + w && mouseY > y + 74f && ry <= mouseY && mouseY <= ry + 18
            if (hov) {
                hovered = e
                Canvas.glass(x + 6f, ry - 2f, w - 12f, 20f, 4f, 2f, 0.1f, 1f, Theme.blur, Theme.outline)
            }
            val n = name(e)
            Fonts.medium95.draw(n.take(28) + (if (n.length > 28) "..." else ""), x + 27f, ry + 1.5f,
                if (selected(e)) Theme.primary else Theme.inactiveText)
            val stack = icon(e)
            val g = Canvas.graphics
            if (stack != null && g != null) {
                Canvas.pose.pushMatrix()
                Canvas.pose.translate(x + 8, ry)
                g.item(stack, 0, 0)
                Canvas.pose.popMatrix()
            }
        }
        Canvas.popScissor()
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int) {
        val x = x
        val y = y
        when {
            x + 6 <= mx && mx <= x + w / 2f - 2 && y + 54 <= my && my <= y + 67 -> {
                all = true
                scroll = 0f
            }
            x + w / 2f <= mx && mx <= x + w - 12 && y + 54 <= my && my <= y + 67 -> {
                all = false
                scroll = 0f
            }
            y + 28 <= my && my <= y + 48 && x + 4 <= mx && mx <= x + 4 + w - 8 -> focused = !focused
        }
        hovered?.let { toggle(it) }
        super.mouseClicked(mx, my, button)
    }

    override fun keyPressed(key: Int) {
        when (key) {
            Keys.ESCAPE, Keys.ENTER -> focused = false
            Keys.BACKSPACE -> {
                search = search.dropLast(1)
                if (search.isEmpty()) focused = false
            }
        }
        super.keyPressed(key)
    }

    override fun charTyped(c: Char) {
        if (!focused || search.length >= 20) return
        search += c
        scroll = 0f
    }
}

/** "Select Items"; lists air too (on purpose). */
class ItemsPopup(private val list: MutableList<Item>, private val onClick: (Item) -> Unit) : ListPopup<Item>("Select Items") {
    override fun entries(): List<Item> = BuiltInRegistries.ITEM.toList()
    override fun name(e: Item): String = ItemStack(e).hoverName.string
    override fun selected(e: Item) = list.contains(e)
    override fun toggle(e: Item) {
        onClick(e)
        if (!list.remove(e)) list.add(e)
    }

    override fun icon(e: Item): ItemStack = e.defaultInstance
}
