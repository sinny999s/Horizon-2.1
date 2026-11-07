package dev.horizon.client.gui.menu

import dev.horizon.client.mixin.ConnectScreenAccessor
import dev.horizon.client.module.misc.AutoReconnect
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.floor

/**
 * Not in the original: the connecting screen ("Connecting to the server...", "Logging in..." and so on) in the menus'
 * style, over the backdrop: a small panel with vanilla's status as its title, live, the server being joined under it, a
 * short bar sweeping along a faint track while it connects, and Cancel, which presses vanilla's own. Vanilla's screen
 * keeps running the connection: its tick (and close) are forwarded to it, and vanilla's code-of-conduct screen, which
 * ticks the connecting screen under it only when that is vanilla's, ticks this one too (CodeOfConductScreenMixin).
 */
class ConnectMenu(val vanilla: ConnectScreen) : Screen(vanilla.title), MenuScreen {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private var cancel: AbstractButton? = null
    private val button = MenuButton("", Menus::back) { e -> cancel?.onPress(e) }
    private val enter = Animation(Easing.OUT_EXPO, 350)
    private var lastNarration = -1L

    override fun init() {
        vanilla.init(width, height)
        cancel = vanilla.children().filterIsInstance<AbstractButton>().firstOrNull()
    }

    override fun added() = enter.reset(0f)

    override fun tick() = vanilla.tick()

    override fun removed() = vanilla.removed()

    override fun onClose() = vanilla.onClose()

    override fun shouldCloseOnEsc() = vanilla.shouldCloseOnEsc()

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Vanilla's narration, which its own render did: "Joining the server" every 2 s.
        val now = Util.getMillis()
        if (now - lastNarration > 2000L) {
            lastNarration = now
            mc.narrator.saySystemNow(Component.translatable("narrator.joining"))
        }
    }

    /** The server being joined: its name, and its address unless the options hide addresses. */
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
        val pw = minOf(260f, w - 40f)
        val inner = pw - 24f
        val where = where()
        val top = if (where.isEmpty()) 34f else 42f
        val bh = 20f
        val ph = top + 2f + 12f + bh + 12f
        val x = floor((w - pw) / 2f)
        val y = floor(((h - ph) / 2f).coerceAtLeast(8f) + (1f - k) * 10f)
        Canvas.alpha = k
        Menus.glass(x, y, pw, ph, 8f, 0xFF3A3A3A.toInt(), 0x40FFFFFF)
        val status = (vanilla as ConnectScreenAccessor).horizon_status().string
        Fonts.semibold12.draw(Menus.fit(status, Fonts.semibold12, inner), x + 12f, y + 11f, Theme.text)
        if (where.isNotEmpty()) Fonts.medium65.draw(Menus.fit(where, Fonts.medium65, inner), x + 12f, y + 26f, Menus.GREY)
        sweep(x + 12f, y + top, inner)
        val b = cancel
        button.label = b?.message?.string ?: "Cancel"
        button.active = b?.active ?: false
        button.draw(x + 12f, y + top + 2f + 12f, inner, bh, mouseX, mouseY)
        Canvas.alpha = 1f
    }

    /** The indeterminate progress: a faint track [w] wide with a short bar sweeping along it, easing in and out. */
    private fun sweep(x: Float, y: Float, w: Float) {
        Menus.rect(x, y, w, 2f, 1f, 0x26FFFFFF)
        val t = (System.currentTimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        val eased = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        val bar = w * 0.28f
        val from = -bar + (w + bar) * eased
        val left = maxOf(0f, from)
        val right = minOf(w, from + bar)
        if (right - left >= 4f) Menus.rect(x + left, y, right - left, 2f, 1f, Menus.FILL)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean = button.click(event)

    override fun keyPressed(event: KeyEvent): Boolean = vanilla.keyPressed(event)

    companion object {
        /** One sweep of the bar, end to end. */
        private const val SWEEP_MS = 1400L
    }
}
