package dev.horizon.client.module.rage

import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * NCP / Vanilla / Strict NCP raise the step-height attribute while on the ground; after a step
 * up NCP and Strict NCP send the fake jump positions of that step height (slowing the tick rate to 1 / their count with
 * [timer]). NCP 2 instead shapes the vertical move against a wall into a jump.
 *
 * Note: a 26.3 server kicks for more than one position packet in a tick; servers on older versions accept the sequence.
 */
class Step : Module("step", Categories.RAGE) {
    enum class Mode { NCP, VANILLA, STRICT_NCP, NCP_2 }

    val mode = enum("mode", Mode.NCP)
    val height = float("height", 2.0f, 1.0f..2.5f) { (mode.value == Mode.NCP || mode.value == Mode.VANILLA) }
    val timer = bool("timer", true) { (mode.value == Mode.NCP || mode.value == Mode.STRICT_NCP) }
    val pauseIfShift = bool("pause-if-shift", false)

    override fun onDisable() {
        setStepHeight(0.6f)
    }

    private fun setStepHeight(h: Float) {
        mc.player?.getAttribute(Attributes.STEP_HEIGHT)?.baseValue = h.toDouble()
    }

    /** The fake jump offsets for a step of [dy] blocks. */
    private fun offsets(dy: Double): DoubleArray? = when ((dy * 10000).toInt()) {
        7500, 10000 -> doubleArrayOf(0.42, 0.753)
        8125, 8750 -> doubleArrayOf(0.39, 0.7)
        15000 -> doubleArrayOf(0.42, 0.75, 1.0, 1.16, 1.23, 1.2)
        20000 -> doubleArrayOf(0.42, 0.78, 0.63, 0.51, 0.9, 1.21, 1.45, 1.43)
        25000 -> doubleArrayOf(0.425, 0.821, 0.699, 0.599, 1.022, 1.372, 1.652, 1.869, 2.019, 1.907)
        else -> null
    }

    init {
        on<PreSyncEvent> {
            val p = mc.player ?: return@on
            if (mode.value == Mode.NCP_2) return@on
            if (pauseIfShift.value && mc.options.keyShift.isDown || p.abilities.flying || p.isHandsBusy || p.isInWater || p.isFallFlying) {
                setStepHeight(0.6f)
                return@on
            }
            setStepHeight(if (!p.onGround()) 0.6f else if (mode.value == Mode.STRICT_NCP) 1.0f else height.value)
            if (mode.value != Mode.NCP && mode.value != Mode.STRICT_NCP) return@on
            val dy = p.y - p.yo
            if (dy <= 0.75 || dy > height.value) return@on
            val offsets = offsets(dy) ?: return@on
            if (offsets.size <= 1) return@on
            if (timer.value) TimerManager.factor = 1.0f / offsets.size
            for (o in offsets) Packets.send(ServerboundMovePlayerPacket.Pos(p.xo, p.yo + o, p.zo, false, p.horizontalCollision))
            if (mode.value == Mode.STRICT_NCP) Packets.send(ServerboundMovePlayerPacket.Pos(p.xo, p.yo + dy, p.zo, false, p.horizontalCollision))
        }
        on<MoveEvent> { e ->
            val p = mc.player ?: return@on
            if (!p.horizontalCollision || mode.value != Mode.NCP_2) return@on
            if (pauseIfShift.value && mc.options.keyShift.isDown) return@on
            e.y = when {
                p.onGround() -> 0.42
                e.y == -0.1552320045166016 -> 0.333
                e.y == -0.230527368912964 -> 0.25
                else -> return@on
            }
            e.cancel()
        }
    }
}
