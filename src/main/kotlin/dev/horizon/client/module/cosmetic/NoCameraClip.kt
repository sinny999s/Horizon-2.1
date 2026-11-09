package dev.horizon.client.module.cosmetic

import dev.horizon.client.event.CameraOffsetEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import net.minecraft.client.CameraType

/**
 * The third-person camera goes through blocks, [distance] blocks back, easing out
 * from the player (600 ms, OUT_QUINT) every time the camera mode changes.
 */
class NoCameraClip : Module("no-camera-clip", Categories.COSMETIC) {
    val distance = float("distance", 3.0f, 1.0f..20.0f)

    private val anim = Animation(Easing.OUT_QUINT, 600)
    private var lastType = CameraType.FIRST_PERSON

    init {
        on<Render3DEvent> {
            val type = mc.options.cameraType
            if (lastType != type) {
                anim.reset(0f)
                lastType = type
            }
            anim.update(1f)
        }
        on<CameraOffsetEvent> { e ->
            e.offset = 1f + (distance.value - 1f) * anim.value
            e.cancel()
        }
    }
}
