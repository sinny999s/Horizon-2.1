package dev.horizon.client.gui.menu

import dev.horizon.client.combat.legacyText
import dev.horizon.client.module.misc.AutoReconnect
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.contents.TranslatableContents
import kotlin.math.floor

/**
 * The disconnected (kicked, lost connection, couldn't connect) screen in the menus' style, over the
 * backdrop: a panel with vanilla's title, the server you were on, the reason in the server's own colours, and at the
 * bottom Back beside Auto Reconnect's Reconnect button, which fills a bar along its bottom while Auto Reconnect counts
 * down. Vanilla's other buttons (report to the server, open the report folder) get a row each above them. Every button
 * presses vanilla's own, and Esc does nothing, as on vanilla's screen.
 */
class DisconnectMenu(val vanilla: DisconnectedScreen) : Screen(vanilla.title), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private class Linked(val vanilla: AbstractButton, val button: MenuButton)

    private var reason = ""
    private var extras = emptyList<Linked>()
    private var bottom = emptyList<Linked>()
    private var reconnect: Linked? = null
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private val bar = Animation(Easing.OUT_CUBIC, 200)

    override fun init() {
        vanilla.init(width, height)
        val widgets = vanilla.children()
        reason = widgets.filterIsInstance<MultiLineTextWidget>().firstOrNull()?.let { legacyText(it.message) } ?: ""
        val buttons = widgets.filterIsInstance<AbstractButton>().filter { it.visible && it.message.string.isNotBlank() }
            .map { b -> Linked(b, MenuButton(b.message.string, Menus.soundFor(b)) { e -> b.onPress(e) }) }
        reconnect = buttons.firstOrNull { AutoReconnect.isButton(it.vanilla) }
        val back = buttons.filter { (it.vanilla.message.contents as? TranslatableContents)?.key in BACK }
        extras = buttons - back.toSet() - listOfNotNull(reconnect).toSet()
        bottom = back + listOfNotNull(reconnect)
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

    /** The server you were on (or were joining): its name, and its address unless the options hide addresses. */
    private fun where(): String {
        val server = AutoReconnect.server() ?: return ""
        if (mc.options.hideServerAddress || server.name == server.ip) return server.name
        return "${server.name}  ·  ${server.ip}"
    }

    override fun draw(mouseX: Float, mouseY: Float) {
        enter.update(1f)
        val k = enter.value
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(300f, w - 40f)
        val inner = pw - 24f
        val font = Fonts.medium75
        var lines = wrap(reason, font, inner)
        val lineH = 10f
        val bh = 20f
        val gap = 5f
        // At most what fits on the screen, the last line cut with "...".
        val fixed = 42f + 10f + (extras.size + 1) * (bh + gap) + 7f
        val most = ((h - 16f - fixed) / lineH).toInt().coerceIn(1, 14)
        if (lines.size > most) lines = lines.take(most - 1) + (Menus.fit(lines[most - 1], font, inner - 12f) + "...")
        val where = where()
        val top = if (where.isEmpty()) 34f else 42f
        val ph = top + lines.size * lineH + 10f + (extras.size + if (bottom.isEmpty()) 0 else 1) * (bh + gap) + 7f - gap + 5f
        val x = floor((w - pw) / 2f)
        val y = floor(((h - ph) / 2f).coerceAtLeast(8f) + (1f - k) * 10f)
        Canvas.alpha = k
        Menus.glass(x, y, pw, ph, 8f, 0xFF3A3A3A.toInt(), 0x40FFFFFF)
        Fonts.semibold12.draw(Menus.fit(title.string, Fonts.semibold12, inner), x + 12f, y + 11f, Theme.text)
        if (where.isNotEmpty()) Fonts.medium65.draw(Menus.fit(where, Fonts.medium65, inner), x + 12f, y + 26f, Menus.GREY)
        var ty = y + top
        for (l in lines) {
            font.draw(l, x + 12f, ty, TEXT)
            ty += lineH
        }
        ty += 10f
        for (l in extras) {
            l.button.label = l.vanilla.message.string
            l.button.active = l.vanilla.active
            l.button.draw(x + 12f, ty, inner, bh, mouseX, mouseY)
            ty += bh + gap
        }
        var bx = x + 12f
        val bw = (inner - gap * (bottom.size - 1)) / bottom.size.coerceAtLeast(1)
        for (l in bottom) {
            l.button.label = l.vanilla.message.string
            l.button.active = l.vanilla.active
            l.button.draw(bx, ty, bw, bh, mouseX, mouseY)
            if (l === reconnect) countdown(bx, ty, bw, bh)
            bx += bw + gap
        }
        Canvas.alpha = 1f
    }

    /** Auto Reconnect's countdown as a light bar filling along the bottom of the Reconnect button; it fades out when not counting. */
    private fun countdown(x: Float, y: Float, w: Float, h: Float) {
        val p = AutoReconnect.progress()
        bar.update(if (p < 0f) 0f else 1f)
        if (bar.value <= 0.01f) return
        val alpha = Canvas.alpha
        Canvas.alpha = alpha * bar.value
        val track = w - 12f
        Menus.rect(x + 6f, y + h - 4f, track, 2f, 1f, 0x26FFFFFF)
        Menus.rect(x + 6f, y + h - 4f, maxOf(2f, track * p.coerceIn(0f, 1f)), 2f, 1f, Menus.FILL)
        Canvas.alpha = alpha
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        for (l in extras + bottom) if (l.button.click(event)) return true
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean = vanilla.keyPressed(event)

    companion object {
        /** The reason's colour where the server gives none. */
        private val TEXT = 0xFFB8B8B8.toInt()

        /** Vanilla's back buttons (to the server list, to the title screen). */
        private val BACK = setOf("gui.toMenu", "gui.toTitle", "gui.back")

        /**
         * [text] (with § colour codes) broken into lines no wider than [w], vanilla's own line breaks kept; each line
         * starts with the colour the one before it ended in.
         */
        fun wrap(text: String, font: dev.horizon.client.render.TextRenderer, w: Float): List<String> {
            val out = ArrayList<String>()
            var carry = ""
            for (para in text.split('\n')) {
                var line = ""
                for (word in para.split(' ')) {
                    val next = if (line.isEmpty()) word else "$line $word"
                    if (font.width(next) <= w || font.width(line) == 0f) line = next
                    else {
                        out += carry + line
                        carry = colorAtEnd(carry + line)
                        line = word
                    }
                }
                out += carry + line
                carry = colorAtEnd(carry + line)
            }
            while (out.isNotEmpty() && font.width(out.last()) == 0f) out.removeAt(out.size - 1)
            return out
        }

        /** The colour code in force at the end of [s] ("" for the default colour). */
        private fun colorAtEnd(s: String): String {
            var code = ""
            var i = 0
            while (i < s.length - 1) {
                if (s[i] != '§') {
                    i++
                    continue
                }
                val c = s[i + 1].lowercaseChar()
                when {
                    c == 'x' && i + 14 <= s.length -> {
                        code = s.substring(i, i + 14)
                        i += 14
                    }
                    c == 'r' -> {
                        code = ""
                        i += 2
                    }
                    c in "0123456789abcdef" -> {
                        code = s.substring(i, i + 2)
                        i += 2
                    }
                    else -> i += 2
                }
            }
            return code
        }
    }
}
