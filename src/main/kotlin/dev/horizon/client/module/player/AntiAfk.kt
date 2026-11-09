package dev.horizon.client.module.player

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.gui.screens.DeathScreen

/**
 * Keeps a server from kicking you for idling while you're away. Every [interval] seconds (give or take
 * a fifth, so it never runs like clockwork) it does the chosen things the server sees a player do: turn the head a
 * little (left, then right next time, so you keep facing the same way), swap to the next hotbar slot and back, sneak
 * for a moment, jump, or step forward and back to where you were (sneaking, so never off an edge). With [respawn],
 * dying respawns you straight away, without Auto Respawn's death command, so a kill while you're gone doesn't leave
 * you on the death screen.
 */
class AntiAfk : Module("anti-afk", Categories.PLAYER) {
    val actions = group("actions", GroupMode.BOOLS)
    val turn = bool("turn", true, parent = actions.value)
    val hotbar = bool("hotbar", true, parent = actions.value)
    val sneak = bool("sneak", true, parent = actions.value)
    val jump = bool("jump", false, parent = actions.value)
    val walk = bool("walk", false, parent = actions.value)
    val interval = int("interval", 30, 5..300)
    val respawn = bool("respawn", true)

    /** When the next round of actions is due (ms). */
    private var next = 0L

    /** Ticks into the current round: sneaking, stepping and swapping back take a few. -1 between rounds. */
    private var tick = -1
    private var turnLeft = false
    private var slot = 0
    /** Where stepping brings you back to: where you were when it began, or after being moved more than 3 blocks. */
    private var homeX = Double.NaN
    private var homeZ = Double.NaN

    /** Set on every tick without a death screen, so each death screen respawns once. */
    private var armed = false

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (mc.gui.screen() is DeathScreen) {
                if (respawn.value && armed) {
                    p.respawn()
                    mc.gui.setScreen(null)
                    armed = false
                }
                return@on
            }
            armed = true
            val now = System.currentTimeMillis()
            if (tick < 0 && now >= next) {
                tick = 0
                next = now + (interval.value * 1000L * Rng.float(0.8f, 1.2f)).toLong()
            }
            if (tick >= 0) act(tick++)
        }
    }

    override fun onEnable() {
        next = System.currentTimeMillis() + interval.value * 1000L
        tick = -1
        homeX = Double.NaN
    }

    override fun onDisable() {
        if (tick >= 0) release()
        tick = -1
    }

    /**
     * Tick [t] of a round. Everything starts on tick 0. Sneaking lasts 4 ticks. Stepping goes 4 ticks forward, then
     * back, letting go early enough that the slide ends at home (at most 10 ticks), sneaking both ways so
     * you can't walk off an edge; with stepping on, the jump waits until you've come to a stop.
     */
    private fun act(t: Int) {
        val p = mc.player ?: return
        val keys = mc.options
        val stepping = walk.value
        if (t == 0) {
            if (turn.value) {
                p.yRot += Rng.float(6f, 14f) * if (turnLeft) -1f else 1f
                turnLeft = !turnLeft
            }
            if (hotbar.value) {
                slot = p.inventory.selectedSlot
                Inventory.selectHotbar((slot + 1) % 9)
            }
            if (sneak.value || stepping) keys.keyShift.setDown(true)
            if (stepping) {
                if (homeX.isNaN() || Math.hypot(p.x - homeX, p.z - homeZ) > 3.0) {
                    homeX = p.x
                    homeZ = p.z
                }
                keys.keyUp.setDown(true)
            } else if (jump.value && p.onGround()) p.jumpFromGround()
        }
        if (t == 2 && hotbar.value) Inventory.selectHotbar(slot)
        if (t == 4) {
            if (!stepping) {
                release()
                tick = -1
                return
            }
            keys.keyUp.setDown(false)
            keys.keyDown.setDown(true)
        }
        if (stepping && t > 4) {
            // How far ahead of home you still are along the way you face, and how fast you're coming back; on the
            // ground you slide about 1.2 ticks' worth after letting go.
            val yaw = Math.toRadians(p.yRot.toDouble())
            val fx = -Math.sin(yaw)
            val fz = Math.cos(yaw)
            val ahead = (p.x - homeX) * fx + (p.z - homeZ) * fz
            val speed = p.deltaMovement.x * fx + p.deltaMovement.z * fz
            if (keys.keyDown.isDown && (ahead + speed * 1.2 <= 0.0 || t >= 14)) keys.keyDown.setDown(false)
            if (!keys.keyDown.isDown && (p.deltaMovement.horizontalDistance() < 0.003 || t >= 24)) {
                release()
                if (jump.value && p.onGround()) p.jumpFromGround()
                tick = -1
            }
        }
    }

    /** Lets go of every key a round may be holding. */
    private fun release() {
        val keys = mc.options
        if (sneak.value || walk.value) keys.keyShift.setDown(false)
        if (walk.value) {
            keys.keyUp.setDown(false)
            keys.keyDown.setDown(false)
        }
    }
}
