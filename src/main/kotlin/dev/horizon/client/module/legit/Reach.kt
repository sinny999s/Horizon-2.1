package dev.horizon.client.module.legit

import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules

/**
 * While on, `Player.blockInteractionRange()` / `entityInteractionRange()` return the settings
 * outright (PlayerMixin) — for every `Player`, the integrated server's included. No packets.
 */
class Reach : Module("reach", Categories.LEGIT) {
    val blocksRange = float("blocks-range", 3.0f, 0.1f..10.0f)
    val entityRange = float("entity-range", 3.0f, 0.1f..10.0f)

    companion object {
        private val module by lazy { Modules.get<Reach>() }

        /** The replacement for `blockInteractionRange()`, or null while reach is off. */
        @JvmStatic
        fun blockRange(): Double? = module.takeIf { it.enabled }?.blocksRange?.value?.toDouble()

        /** The replacement for `entityInteractionRange()`, or null while reach is off. */
        @JvmStatic
        fun entityRange(): Double? = module.takeIf { it.enabled }?.entityRange?.value?.toDouble()
    }
}
