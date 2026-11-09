package dev.horizon.client.module.basic

import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.MoveFixKind
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.phys.Vec2
import kotlin.math.roundToInt

/**
 * While moving any way but straight forward, the server yaw turns to the way the keys
 * point (a FORCE request, so travel uses it) and the input is turned to match, so the player walks forward.
 */
class LegitStrafe : Module("legit-strafe", Categories.BASIC) {
    /** A rotation was requested this tick; the next input tick uses it up. */
    private var rotated = false

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val o = mc.options
            if (!(Movement.moving() && (!o.keyUp.isDown || o.keyDown.isDown))) return@on
            RotationManager.rotate(Rotation(Movement.keysYaw(), p.xRot, false, MoveFixKind.FORCE), 0)
            rotated = true
        }
        on<KeyboardInputEvent>(10) {
            if (!rotated) return@on
            rotated = false
            val p = mc.player ?: return@on
            val forward = p.input.moveVector.y
            val strafe = p.input.moveVector.x
            val a = (p.yRot - Movement.keysYaw()) * (Math.PI / 180.0).toFloat()
            val c = Math.cos(a.toDouble()).toFloat()
            val s = Math.sin(a.toDouble()).toFloat()
            (p.input as ClientInputAccessor).horizon_setMoveVector(
                Vec2((strafe * c - forward * s).roundToInt().toFloat(), (forward * c + strafe * s).roundToInt().toFloat()))
        }
    }
}
