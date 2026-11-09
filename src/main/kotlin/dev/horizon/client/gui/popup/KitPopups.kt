package dev.horizon.client.gui.popup

import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Modules
import dev.horizon.client.module.equip.InventoryManager
import dev.horizon.client.module.equip.Kits
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/** A popup button: a glass rect that brightens on hover (200 ms OUT_QUINT); any mouse button clicks it. */
private class PopupButton(val w: Float, val h: Float, val index: Int, val onClick: () -> Unit) {
    var x = 0f
    var y = 0f
    private val hover = Animation(Easing.OUT_QUINT, 200)

    fun render(px: Float, py: Float, mx: Int, my: Int) {
        if (px != -1f && py != -1f) {
            x = px
            y = py
        }
        hover.update(if (hit(mx, my)) 1f else 0f)
        Canvas.glass(x, y, w, h, 3f, 2f, 0.25f + hover.value / 3f, 1f, Theme.inactiveModule, Theme.inactiveModuleOutline)
    }

    fun click(mx: Int, my: Int) {
        if (hit(mx, my)) onClick()
    }

    private fun hit(mx: Int, my: Int) = x <= mx && mx <= x + w && y <= my && my <= y + h
}

/**
 * The kit editor for "inventory-manager": the current kit's 36 slots (main inventory rows, then the
 * hotbar); a slot opens an item picker, "select" the kit selector. Either opens as its sub-popup [sub].
 */
class KitPopup : Popup() {
    private val slots = LinkedHashMap<PopupButton, Item>()
    private val select = PopupButton(40f, 15f, 0) { sub = KitSelectPopup(Kits.list.map { it.id }, ::selectKit, ::deleteKit) }

    init {
        rebuild()
    }

    /** Each slot's picker starts on the item the slot had at this point. */
    private fun rebuild() {
        w = 188f
        h = 119f
        slots.clear()
        var index = 0
        for (row in 0 until 4) for (col in 0 until 9) {
            val i = index
            val item = Kits.current.items[i] ?: Items.AIR
            slots[PopupButton(18f, 18f, i) { openItemPicker(item, i) }] = item
            index++
        }
    }

    override fun content(mouseX: Int, mouseY: Int) {
        val x = x
        val y = y
        Canvas.quad(x + 6, y + 91.5f, w - 12, 0.5f, 0x44FFFFFF)
        select.render(x + 6, y + 5, mouseX, mouseY)
        Fonts.medium95.drawCentered("select", x + 25f, y + 11f, Theme.inactiveText)
        Fonts.medium95.drawCentered("Current kit: " + Kits.current.id, x + w / 2f, y + 11f, Theme.inactiveText)
        slots.entries.forEachIndexed { i, (button, item) ->
            val rowOff = when (i) {
                in 0 until 9 -> 0f
                in 9 until 18 -> 20f
                in 18 until 27 -> 40f
                else -> 65f
            }
            button.render(i % 9 * 20f + x + 5f, rowOff + y + 30f, mouseX, mouseY)
            val g = Canvas.graphics
            if (sub == null && g != null) {
                Canvas.pose.pushMatrix()
                Canvas.pose.translate(i % 9 * 20f + x + 6f, rowOff + y + 31f)
                g.item(item.defaultInstance, 0, 0)
                Canvas.pose.popMatrix()
            }
        }
        sub?.render(mouseX, mouseY)
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int) {
        super.mouseClicked(mx, my, button)
        sub?.let {
            it.mouseClicked(mx, my, button)
            return
        }
        for (b in slots.keys) b.click(mx, my)
        select.click(mx, my)
    }

    override fun keyPressed(key: Int) {
        super.keyPressed(key)
        sub?.keyPressed(key)
    }

    override fun charTyped(c: Char) {
        super.charTyped(c)
        sub?.charTyped(c)
    }

    override fun mouseScrolled(amount: Double) {
        super.mouseScrolled(amount)
        sub?.mouseScrolled(amount)
    }

    private fun selectKit(name: String) {
        Kits.list.firstOrNull { it.id == name }?.let {
            Kits.current = it
            rebuild()
        }
        sub!!.closing = true
    }

    private fun deleteKit(name: String) {
        Kits.list.removeIf { it.id == name }
        rebuild()
    }

    private fun openItemPicker(item: Item, index: Int) {
        sub = ItemsPopup(mutableListOf(item)) { newItem ->
            setSlot(index, newItem)
            sub!!.closing = true
        }
    }

    private fun setSlot(index: Int, item: Item) {
        for (b in slots.keys.filter { it.index == index }) slots[b] = item
        Kits.current.items[index] = item
    }

    companion object {
        /** The open sub-popup; a popup that finishes closing detaches it instead of itself. */
        var sub: Popup? = null
    }
}

/**
 * The kit selector: a name field and "+" (a new kit from the current inventory), and the kits as they were
 * when it opened (click selects, the red box deletes).
 */
class KitSelectPopup(private val names: List<String>, private val onSelect: (String) -> Unit, private val onRemove: (String) -> Unit) : Popup() {
    private var hovered: String? = null
    private var focused = false
    private var text = ""
    private var plusHovered = false
    private var removeHovered = false

    override fun content(mouseX: Int, mouseY: Int) {
        val x = x
        val y = y
        plusHovered = x + w - 25 <= mouseX && mouseX <= x + w - 5 && y + 28 <= mouseY && mouseY <= y + 48
        Fonts.semibold12.drawGradientCentered("Select", x + w / 2f, y + 3, 10)
        Canvas.glass(x + 5f, y + 28f, w - 33f, 20f, 4f, 0f, 0.1f, 1f, Colors.brighter(Theme.blur), Theme.outline)
        Canvas.glass(x + w - 24.5f, y + 28f, 20.5f, 20f, 4f, 0f, 0.1f, 1f, Colors.brighter(Theme.blur), Theme.outline)
        Fonts.semibold12.drawCentered("+", x + w - 15f, y + 35.5f, if (plusHovered) Theme.text else Theme.inactiveText)
        val shown = if (focused) text + (if (System.currentTimeMillis() % 700 > 350) "_" else "") else "Enter name"
        Fonts.semibold12.draw(shown, x + 7f, y + 29f, if (focused) Theme.text else Theme.inactiveText)
        Canvas.glass(x + 4f, y + 53f, w - 8f, h - 57f, 4f, 2f, 0.1f, 1f, Colors.brighter(Theme.blur), Theme.outline)
        hovered = null
        removeHovered = false
        Canvas.pushScissor(x, y + 55f, x + w, y + h - 6f)
        names.sorted().forEachIndexed { n, name ->
            val rowY = y + 58f + n * 18f + scroll
            if (rowY < y + 40 || rowY > y + h) return@forEachIndexed
            if (x <= mouseX && mouseX <= x + w && mouseY > y + 58f && rowY <= mouseY && mouseY <= rowY + 17) {
                hovered = name
                Canvas.glass(x + 6f, rowY - 2f, w - 12f, 20f, 4f, 2f, 0.1f, 1f, Theme.blur, Theme.outline)
            }
            Fonts.medium95.draw(if (name.length > 28) name.substring(0, 28) + "..." else name, x + 10f, rowY + 1,
                if (name == Kits.current.id) Theme.primary else Theme.inactiveText)
            val delHover = x + w - 20 <= mouseX && mouseX <= x + w - 10 && rowY + 3 <= mouseY && mouseY <= rowY + 13
            val fill = if (delHover) 0xB2FF0000.toInt() else 0x7FFF0000
            Canvas.rect(x + w - 20f, rowY + 3f, 10f, 10f, 3f, fill, if (delHover) 2f else 1f, if (delHover) 0xFFFF0000.toInt() else 0x7FFF0000)
            if (!removeHovered && delHover) removeHovered = true
        }
        Canvas.popScissor()
    }

    override fun mouseClicked(mx: Int, my: Int, button: Int) {
        val h = hovered
        if (removeHovered && h != null) {
            onRemove(h)
            return
        }
        if (plusHovered) create()
        if (y + 28 <= my && my <= y + 48 && x + 4 <= mx && mx <= x + w - 33) focused = !focused
        hovered?.let { onSelect(it) }
        super.mouseClicked(mx, my, button)
    }

    override fun keyPressed(key: Int) {
        when (key) {
            Keys.ESCAPE -> focused = false
            Keys.ENTER -> create()
            Keys.BACKSPACE -> {
                text = text.dropLast(1)
                if (text.isEmpty()) focused = false
            }
        }
        super.keyPressed(key)
    }

    override fun charTyped(c: Char) {
        if (!focused || text.length >= 20) return
        text += c
        scroll = 0f
    }

    /** A kit from the current inventory becomes current, and a fresh editor replaces the old one. */
    private fun create() {
        if (text.isBlank()) return
        if (Kits.list.map { it.id }.contains(text)) return
        val kit = Modules.get<InventoryManager>().snapshot(text)
        Kits.list += kit
        Kits.current = kit
        text = ""
        focused = false
        closing = true
        PopupScreen.popup = KitPopup()
    }
}
