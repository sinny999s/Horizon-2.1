package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.SprintEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.block.LiquidBlock

/**
 * DolphinGrace gives the player a client-side Dolphin's Grace while swimming, Vanilla
 * sets the swimming move to [speed] blocks a tick along the input, Legit keeps sprint on in (or just above) water.
 */
class WaterSpeed : Module("water-speed", Categories.RAGE) {
    @Suppress("EnumEntryName")
    enum class Mode { DolphinGrace, Vanilla, Legit }

    val mode = enum("mode", Mode.DolphinGrace)
    val speed = float("speed", 2.0f, 1.0f..10.0f)

    override fun onDisable() {
        if (mode.value == Mode.DolphinGrace) mc.player?.removeEffect(MobEffects.DOLPHINS_GRACE)
    }

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (mode.value != Mode.DolphinGrace) return@on
            if (p.isSwimming) p.addEffect(MobEffectInstance(MobEffects.DOLPHINS_GRACE, 2, 2))
            else p.removeEffect(MobEffects.DOLPHINS_GRACE)
        }
        on<MoveEvent> { e ->
            val p = mc.player ?: return@on
            if (mode.value == Mode.Vanilla && p.isSwimming) {
                Movement.strafe(e, speed.value.toDouble())
                e.cancel()
            }
        }
        on<SprintEvent> { e ->
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (!enabled || mode.value != Mode.Legit) return@on
            if (p.isInWater || level.getBlockState(BlockPos.containing(p.position().add(0.0, -0.5, 0.0))).block is LiquidBlock) {
                e.sprinting = true
            }
        }
    }
}
