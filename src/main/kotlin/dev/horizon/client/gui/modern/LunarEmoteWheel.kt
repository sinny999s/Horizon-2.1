package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.lunar.LunarEmotes
import dev.horizon.client.lunar.LunarProfileEdits
import dev.horizon.client.lunar.LunarSocial
import dev.horizon.client.render.Canvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lunar's emote wheel. Held open by the Lunar menu's emote key: the emotes in your wheel's slots
 * (set with right-click in the locker's Emotes tab, saved on your account) sit around a ring; point at one and let go
 * of the key (or click) to play it ([LunarSocial.playEmote]: for other Lunar players too when you own it).
 */
object LunarEmoteWheel : Screen(Component.literal("horizon-lunar-emote-wheel")) {
    private const val R = 62f
    private val mc get() = Minecraft.getInstance()
    private var mouseX = 0f
    private var mouseY = 0f
    private var opened = 0L

    fun open() {
        opened = System.currentTimeMillis()
        mc.gui.setScreen(this)
    }

    override fun isPauseScreen() = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
    }

    /** The slot the mouse points at (null near the middle). */
    private fun hovered(): Int? {
        val cx = width / 2f
        val cy = height / 2f
        val dx = mouseX - cx
        val dy = mouseY - cy
        if (hypot(dx, dy) < 18f) return null
        val a = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360 + 180.0 / LunarProfileEdits.SLOTS) % 360
        return (a / (360.0 / LunarProfileEdits.SLOTS)).toInt()
    }

    /** One frame (from Overlay's screen layer). */
    fun draw() {
        val cx = width / 2f
        val cy = height / 2f
        val t = ((System.currentTimeMillis() - opened) / 150f).coerceIn(0f, 1f)
        Canvas.alpha = t
        Ui.box(cx - R - 30f, cy - R - 30f, (R + 30f) * 2f, (R + 30f) * 2f, R + 30f, 0x99000000.toInt(), 0.5f, 0x22FFFFFF)
        val wheel = LunarProfileEdits.wheel
        val names = LunarEmotes.emotes().associate { it.id to it.name.split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
        val h = hovered()
        for (s in 0 until LunarProfileEdits.SLOTS) {
            val a = Math.toRadians(s * 360.0 / LunarProfileEdits.SLOTS)
            val x = cx + (sin(a) * R).toFloat()
            val y = cy - (cos(a) * R).toFloat()
            val id = wheel[s]
            val on = h == s && id != null
            Ui.box(x - 22f, y - 14f, 44f, 28f, 8f, if (on) Tok.alpha(Tok.a1, 0.45f) else 0x55000000, 0.5f, if (on) Tok.a1 else 0x22FFFFFF)
            if (id == null) {
                Ui.textC(Type.tiny, "empty", x, y, Tok.T4)
                continue
            }
            Ui.icon("play", x - 4f, y - 10f, 8f, if (on) Tok.WHITE else Tok.T2)
            Ui.textC(Type.tiny, Ui.fit(Type.tiny, names[id] ?: "#$id", 42f), x, y + 6f, if (on) Tok.WHITE else Tok.T1)
        }
        val center = h?.let { wheel[it] }?.let { names[it] } ?: if (wheel.isEmpty()) "No emotes on your wheel" else "Emotes"
        Ui.textC(Type.small, Ui.fit(Type.small, center, 70f), cx, cy, Tok.T1)
        if (wheel.isEmpty()) Ui.textC(Type.tiny, "Right-click emotes in the locker", cx, cy + R + 40f, Tok.T2)
        Canvas.alpha = 1f
    }

    /** Plays the slot pointed at and closes. */
    fun release() {
        val id = hovered()?.let { LunarProfileEdits.wheel[it] }
        onClose()
        if (id != null) LunarSocial.playEmote(id)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == Keys.MOUSE_LEFT) release()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == Keys.ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    /** For ShotBot: opens the wheel. */
    @JvmStatic
    fun testOpen() = open()

    /** For ShotBot: the mouse at slot [slot]'s place. */
    @JvmStatic
    fun testPoint(slot: Int) {
        val a = Math.toRadians(slot * 360.0 / LunarProfileEdits.SLOTS)
        mouseX = width / 2f + (sin(a) * R).toFloat()
        mouseY = height / 2f - (cos(a) * R).toFloat()
    }
}
