package dev.horizon.client.module.cosmetic

import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.CrosshairEvent
import dev.horizon.client.event.JumpEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.attack.pickAlong
import dev.horizon.client.module.attack.pickedEntity
import net.minecraft.client.CameraType
import net.minecraft.util.profiling.Profiler

/**
 * Third person, the mouse turns only the camera; the rotation the player had when it was
 * enabled ([rotation]) is what the server gets, what movement, jumps and the crosshair use, and what is restored on disable.
 */
class FreeLook : Module("free-look", Categories.COSMETIC) {
    /** The rotation manager snaps it to the sensitivity grid in place. */
    var rotation = Rotation(0f, 0f)

    /** The free rotation while travel runs with [rotation]. */
    private var saved = Rotation(0f, 0f)

    init {
        on<PlayerUpdateEvent> { RotationManager.rotate(rotation, 0) }
        on<JumpEvent> { e ->
            e.yaw = rotation.yaw
            e.cancel()
        }
        on<TravelEvent> { e ->
            val p = mc.player ?: return@on
            if (e.pre) {
                saved = Rotation(p.yRot, p.xRot)
                p.yRot = rotation.yaw
                p.xRot = rotation.pitch
            } else {
                p.yRot = saved.yaw
                p.xRot = saved.pitch
            }
        }
        on<CrosshairEvent> { e ->
            Profiler.get().pop()
            e.cancel()
            if (mc.cameraEntity == null || mc.level == null) return@on
            val p = mc.player ?: return@on
            val hit = pickAlong(p.blockInteractionRange(), p.entityInteractionRange(), rotation.yaw, rotation.pitch)
            mc.hitResult = hit
            mc.crosshairPickEntity = hit.pickedEntity()
        }
    }

    override fun onEnable() {
        val p = mc.player
        if (p == null || mc.level == null) {
            disable()
            return
        }
        rotation = Rotation(p.yRot, p.xRot)
        mc.options.cameraType = CameraType.THIRD_PERSON_BACK
    }

    override fun onDisable() {
        val p = mc.player ?: return
        if (mc.level == null) return
        mc.options.cameraType = CameraType.FIRST_PERSON
        p.yRot = rotation.yaw
        p.xRot = rotation.pitch
    }
}
