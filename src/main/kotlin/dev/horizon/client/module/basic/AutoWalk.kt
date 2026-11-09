package dev.horizon.client.module.basic

import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import kotlin.math.roundToInt

/**
 * Walks forward for you (holds the forward key, menus open or not), sprinting with [sprint]. [snap]
 * turns you to the nearest 45° as it's switched on, to follow a highway or an axis; you can still look and steer.
 */
class AutoWalk : Module("auto-walk", Categories.BASIC) {
    val sprint = bool("sprint", true)
    val snap = bool("snap", true)

    init {
        on<TickEvent> {
            if (mc.player == null) return@on
            mc.options.keyUp.setDown(true)
            if (sprint.value) mc.options.keySprint.setDown(true)
        }
    }

    override fun onEnable() {
        val p = mc.player ?: return
        if (!snap.value) return
        p.yRot = (p.yRot / 45f).roundToInt() * 45f
        p.yRotO = p.yRot
    }

    override fun onDisable() {
        mc.options.keyUp.setDown(false)
        if (sprint.value) mc.options.keySprint.setDown(false)
    }
}
