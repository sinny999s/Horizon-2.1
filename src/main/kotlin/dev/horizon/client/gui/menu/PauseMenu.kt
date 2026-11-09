package dev.horizon.client.gui.menu

import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import kotlin.math.floor

/**
 * The pause menu in the client's style, over the blurred world: a panel with where you are (the world,
 * or the server, its address and how many are online) and vanilla's buttons in vanilla's rows, pressing vanilla's own
 * (leaving and saving included); the feedback and bug-report links are left out.
 */
class PauseMenu(val vanilla: PauseScreen) : Screen(Component.translatable("menu.game")), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private class Linked(val vanilla: AbstractButton, val button: MenuButton)

    private var rows = emptyList<List<Linked>>()
    private val enter = Animation(Easing.OUT_EXPO, 350)

    override fun init() {
        vanilla.init(width, height)
        val buttons = vanilla.children().filterIsInstance<AbstractButton>().filter {
            it.visible && it.message.string.isNotBlank() && (it.message.contents as? TranslatableContents)?.key !in LEFT_OUT
        }
        rows = buttons.groupBy { it.y }.toSortedMap().values.map { row ->
            row.sortedBy { it.x }.map { b -> Linked(b, MenuButton(b.message.string, Menus.soundFor(b)) { e -> b.onPress(e) }) }
        }
    }

    override fun added() = enter.reset(0f)

    override fun tick() = vanilla.tick()

    override fun removed() = vanilla.removed()

    override fun onClose() = vanilla.onClose()

    override fun isPauseScreen() = vanilla.isPauseScreen

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun draw(mouseX: Float, mouseY: Float) {
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(240f, w - 40f)
        val bh = 20f
        val gap = 5f
        val ph = 46f + rows.size * (bh + gap) + 8f
        val x = floor((w - pw) / 2f)
        val y = floor((h - ph) / 2f + (1f - k) * 10f)
        Canvas.alpha = k
        Menus.glass(x, y, pw, ph, 8f, 0xFF5A5A5A.toInt(), 0x40FFFFFF)
        Fonts.semibold12.draw(title.string, x + 12f, y + 11f, Theme.text)
        Fonts.medium65.draw(Menus.fit(where(), Fonts.medium65, pw - 24f), x + 12f, y + 26f, Menus.GREY)
        var ry = y + 42f
        for (row in rows) {
            val total = row.sumOf { it.vanilla.width }.toFloat()
            val avail = pw - 24f - gap * (row.size - 1)
            var rx = x + 12f
            for (l in row) {
                l.button.label = l.vanilla.message.string
                l.button.active = l.vanilla.active
                val bw = avail * l.vanilla.width / total
                l.button.draw(rx, ry, bw, bh, mouseX, mouseY)
                rx += bw + gap
            }
            ry += bh + gap
        }
        Canvas.alpha = 1f
    }

    /** The world you're in, or the server: its name, address and how many are online. */
    private fun where(): String {
        val server = mc.singleplayerServer
        if (mc.isLocalServer && server != null) return "Singleplayer  -  " + server.worldData.levelName
        val data = mc.currentServer ?: return "Multiplayer"
        val online = mc.connection?.onlinePlayers?.size ?: 0
        val address = if (mc.options.hideServerAddress) "" else "  -  " + data.ip
        return data.name + address + "  -  $online online"
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != Keys.MOUSE_LEFT) return false
        for (row in rows) for (l in row) if (l.button.click(event)) return true
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == Keys.ESCAPE) Menus.back()
        return vanilla.keyPressed(event)
    }

    private companion object {
        val LEFT_OUT = setOf("menu.sendFeedback", "menu.reportBugs")
    }
}
