package dev.horizon.client.hud.widgets

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.hud.HudType
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket

/**
 * Keystrokes HUD widget displaying WASD, optional Sneak/Sprint, LMB/RMB with live rolling 1-second
 * CPS counters (counting both physical clicks and synthetic clicks from AutoClicker / TriggerBot / Aura), and a spacebar
 * indicator, rendered in Horizon's frosted-glass HUD style with smooth theme-accent key-press animations.
 */
object KeystrokesWidget : HudWidget("keystrokes-widget") {
    val mouseButtons = bool("mouse-buttons", true)
    val cps = bool("cps", true)
    val spacebar = bool("spacebar", true)
    val sneakSprint = bool("sneak-sprint", false)

    private const val KEY_W = 19f
    private const val KEY_H = 19f
    private const val GAP = 2f
    private const val TOTAL_W = KEY_W * 3f + GAP * 2f
    private const val HALF_W = (TOTAL_W - GAP) / 2f

    private class KeyBox(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val t: Float,
        val isSpace: Boolean = false
    )

    private val drawnKeys = ArrayList<KeyBox>(10)
    private val leftClicks = ArrayDeque<Long>()
    private val rightClicks = ArrayDeque<Long>()

    @Volatile
    private var lastLeftMs = 0L

    @Volatile
    private var lastRightMs = 0L

    init {
        pos.x = 0.0052083335f
        pos.y = 0.22f

        EventBus.on<AfterSendPacketEvent>(this) { e ->
            if (mc.player == null || mc.level == null) return@on
            when (e.packet) {
                is ServerboundPunchPacket, is ServerboundAttackPacket -> recordLeftClick()
                is ServerboundUseItemOnPacket, is ServerboundUseItemPacket -> recordRightClick()
            }
        }
    }

    fun onPhysicalMouse(button: Int, action: Int) {
        if (action != 1 || mc.player == null || mc.level == null || mc.gui.screen() != null) return
        when (button) {
            Keys.MOUSE_LEFT -> recordLeftClick()
            Keys.MOUSE_RIGHT -> recordRightClick()
        }
    }

    @Synchronized
    private fun recordLeftClick() {
        val now = System.currentTimeMillis()
        if (now - lastLeftMs < 8L) return
        lastLeftMs = now
        leftClicks.addLast(now)
    }

    @Synchronized
    private fun recordRightClick() {
        val now = System.currentTimeMillis()
        if (now - lastRightMs < 8L) return
        lastRightMs = now
        rightClicks.addLast(now)
    }

    @Synchronized
    private fun currentCps(now: Long): Pair<Int, Int> {
        val cutoff = now - 1000L
        while (leftClicks.isNotEmpty() && leftClicks.first() < cutoff) leftClicks.removeFirst()
        while (rightClicks.isNotEmpty() && rightClicks.first() < cutoff) rightClicks.removeFirst()
        return leftClicks.size to rightClicks.size
    }

    private fun keyDown(mapping: net.minecraft.client.KeyMapping, inWorldNoScreen: Boolean): Boolean =
        inWorldNoScreen && (mapping.isDown || PlayerState.physical(mapping))

    override fun render() {
        drawnKeys.clear()
        val now = System.currentTimeMillis()
        val (lCps, rCps) = currentCps(now)
        val active = mc.player != null && mc.gui.screen() == null

        val wDown = keyDown(mc.options.keyUp, active)
        val aDown = keyDown(mc.options.keyLeft, active)
        val sDown = keyDown(mc.options.keyDown, active)
        val dDown = keyDown(mc.options.keyRight, active)
        val jumpDown = keyDown(mc.options.keyJump, active)
        val sneakDown = keyDown(mc.options.keyShift, active)
        val sprintDown = keyDown(mc.options.keySprint, active) || (active && mc.player?.isSprinting == true)
        val lmbDown = keyDown(mc.options.keyAttack, active) || (active && now - lastLeftMs < 65L)
        val rmbDown = keyDown(mc.options.keyUse, active) || (active && now - lastRightMs < 65L)

        var curY = top

        // Row 1: W (centered)
        drawKey("ks:w", left + KEY_W + GAP, curY, KEY_W, KEY_H, "W", null, wDown)
        curY += KEY_H + GAP

        // Row 2: A, S, D
        drawKey("ks:a", left, curY, KEY_W, KEY_H, "A", null, aDown)
        drawKey("ks:s", left + KEY_W + GAP, curY, KEY_W, KEY_H, "S", null, sDown)
        drawKey("ks:d", left + (KEY_W + GAP) * 2f, curY, KEY_W, KEY_H, "D", null, dDown)
        curY += KEY_H + GAP

        // Optional Row: Sneak / Sprint
        if (sneakSprint.value) {
            val rowH = 14f
            drawKey("ks:snk", left, curY, HALF_W, rowH, "SNK", null, sneakDown)
            drawKey("ks:spr", left + HALF_W + GAP, curY, HALF_W, rowH, "SPR", null, sprintDown)
            curY += rowH + GAP
        }

        // Optional Row: Mouse Buttons (with or without CPS) or standalone CPS bar
        if (mouseButtons.value) {
            val rowH = if (cps.value) 19f else 15f
            val lSub = if (cps.value) "$lCps CPS" else null
            val rSub = if (cps.value) "$rCps CPS" else null
            drawKey("ks:lmb", left, curY, HALF_W, rowH, "LMB", lSub, lmbDown)
            drawKey("ks:rmb", left + HALF_W + GAP, curY, HALF_W, rowH, "RMB", rSub, rmbDown)
            curY += rowH + GAP
        } else if (cps.value) {
            val rowH = 13f
            drawKey("ks:cps", left, curY, TOTAL_W, rowH, "$lCps  |  $rCps CPS", null, lmbDown || rmbDown)
            curY += rowH + GAP
        }

        // Optional Row: Spacebar
        if (spacebar.value) {
            val spaceH = 12f
            drawSpaceKey("ks:space", left, curY, TOTAL_W, spaceH, jumpDown)
            curY += spaceH + GAP
        }

        size(TOTAL_W, (curY - top - GAP).coerceAtLeast(KEY_H))
    }

    private fun drawKey(
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        label: String,
        subLabel: String?,
        pressed: Boolean
    ) {
        val t = Ui.anim(id, if (pressed) 1f else 0f, 110, Easing.OUT_QUAD)
        plate(x, y, w, h, radius = 3.5f)
        drawnKeys += KeyBox(x, y, w, h, t, isSpace = false)

        val textColor = Colors.lerp(Tok.T1, Tok.WHITE, t)
        if (subLabel == null) {
            val lw = tw(label, HudType.semi)
            ctext(label, x + (w - lw) / 2f, y + h / 2f, textColor, HudType.semi)
        } else {
            val lw = tw(label, HudType.semi)
            val sw = tw(subLabel, HudType.tiny)
            val subColor = Colors.lerp(Tok.T2, Tok.T1, t)
            ctext(label, x + (w - lw) / 2f, y + h / 2f - 3.1f, textColor, HudType.semi)
            ctext(subLabel, x + (w - sw) / 2f, y + h / 2f + 3.9f, subColor, HudType.tiny)
        }
    }

    private fun drawSpaceKey(id: String, x: Float, y: Float, w: Float, h: Float, pressed: Boolean) {
        val t = Ui.anim(id, if (pressed) 1f else 0f, 110, Easing.OUT_QUAD)
        plate(x, y, w, h, radius = 3.5f)
        drawnKeys += KeyBox(x, y, w, h, t, isSpace = true)
    }

    override fun postRender() {
        for (k in drawnKeys) {
            if (k.t > 0.01f) {
                val fill = Colors.withAlpha(Tok.mid, 0.42f * k.t)
                val border = Colors.withAlpha(Tok.light(Tok.a1, 0.35f), 0.72f * k.t)
                Ui.box(k.x, k.y, k.w, k.h, 3.5f, fill, 0.6f, border)
            }
            if (k.isSpace) {
                val barW = 22f + 6f * k.t
                val barH = 1.6f
                val barColor = Colors.lerp(Tok.T2, Tok.WHITE, k.t)
                Ui.box(k.x + (k.w - barW) / 2f, k.y + (k.h - barH) / 2f, barW, barH, 0.8f, barColor)
            }
        }
    }
}
