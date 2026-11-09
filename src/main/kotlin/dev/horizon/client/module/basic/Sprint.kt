package dev.horizon.client.module.basic

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import net.minecraft.world.entity.player.Input

/**
 * Two modes:
 * - [Mode.VANILLA] presses sprint through [KeyboardInputEvent] (both `input.keyPresses` for 26.3 and `options.keySprint`
 *   for ViaFabricPlus `<= 1.21.4` / `1.8.9`) when moving forward so `LocalPlayer.aiStep` applies all normal vanilla
 *   sprint rules (forward-only, hunger, sneak, item use, collisions, no omni FOV zoom).
 * - [Mode.OMNI] sprints from `TickEvent` whenever moving in any direction.
 */
class Sprint : Module("sprint", Categories.BASIC) {
    enum class Mode { VANILLA, OMNI }

    val mode = enum("mode", Mode.VANILLA)
    val pauseInWater = bool("pause-in-water", false)

    /**
     * Skip the next forced sprint: set by Aura's sprint reset. Only this module's tick clears it, so
     * with Sprint off it stays set and Aura keeps sprinting off (on purpose).
     */
    @Volatile
    var skipOnce = false

    private var skipInputTick = false
    private var holdingKey = false

    /** Restores `options.keySprint` to its physical state when [Mode.VANILLA] was holding it. */
    fun releaseKey() {
        if (holdingKey) {
            holdingKey = false
            mc.options.keySprint.let { it.isDown = PlayerState.physical(it) }
        }
    }

    override fun onDisable() {
        skipInputTick = false
        releaseKey()
    }

    init {
        on<TickEvent>(10) {
            val p = mc.player ?: run {
                releaseKey()
                return@on
            }
            if (mc.level == null) {
                releaseKey()
                return@on
            }
            if (mode.value != Mode.VANILLA) releaseKey()
            if (skipOnce) {
                skipOnce = false
                skipInputTick = true
                releaseKey()
                return@on
            }
            skipInputTick = false
            if (mode.value != Mode.OMNI) return@on
            val mv = p.input.moveVector
            if (mv.y == 0f && mv.x == 0f) return@on
            if (pauseInWater.value && p.isUnderWater) return@on
            if (p.isFallFlying) return@on
            p.isSprinting = true
        }

        on<KeyboardInputEvent>(-5) {
            if (mode.value != Mode.VANILLA) {
                releaseKey()
                return@on
            }
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            if (skipInputTick || skipOnce) {
                skipInputTick = false
                skipOnce = false
                releaseKey()
                return@on
            }
            if (Modules.get<Aura>().sprintHook.resetting || (pauseInWater.value && p.isUnderWater)) {
                releaseKey()
                return@on
            }
            val keys = p.input.keyPresses
            if (p.input.moveVector.y <= 1.0e-5f || keys.shift()) {
                releaseKey()
                return@on
            }
            // ViaFabricPlus <= 1.21.4 (including 1.8.9) skips 26.3's `input.keyPresses.sprint()` and reads `keySprint.isDown`.
            mc.options.keySprint.isDown = true
            holdingKey = true
            if (!keys.sprint()) {
                p.input.keyPresses = Input(
                    keys.forward(),
                    keys.backward(),
                    keys.left(),
                    keys.right(),
                    keys.jump(),
                    keys.shift(),
                    true
                )
            }
        }
    }
}
