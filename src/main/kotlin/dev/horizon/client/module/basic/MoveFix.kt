package dev.horizon.client.module.basic

import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * No handlers of its own; the rotation manager (`RotationManager`) reads [enabled] and [mode] live every time a movement
 * hook fires.
 */
class MoveFix : Module("move-fix", Categories.BASIC) {
    enum class Mode { FREE, FOCUS }

    val mode = enum("mode", Mode.FREE)
}
