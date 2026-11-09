package dev.horizon.client.module.rage

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation

/**
 * Climbs walls. Against a wall each tick (PlayerUpdate priority -10): Matrix fakes ground
 * every [delay] ticks and jumps then, Default sets 0.21 up, WaterBucket (jump held, every other tick) uses a water
 * bucket looking down. Before the move packet: Matrix 2 (jump held, falling) fakes ground with a 0.481 lift, FT Fence
 * (moving) jumps off the wall.
 */
class Spider : Module("spider", Categories.RAGE) {
    enum class Mode { DEFAULT, MATRIX, MATRIX_2, WATER_BUCKET, FT_FENCE }

    val mode = enum("mode", Mode.DEFAULT)
    val delay = int("delay", 2, 1..15)

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (!p.horizontalCollision) return@on
            when (mode.value) {
                Mode.MATRIX -> {
                    p.setOnGround(p.tickCount % delay.value == 0)
                    if (p.onGround()) p.setDeltaMovement(p.deltaMovement.x(), 0.42, p.deltaMovement.z())
                }
                Mode.DEFAULT -> p.setDeltaMovement(p.deltaMovement.x(), 0.21, p.deltaMovement.z())
                Mode.WATER_BUCKET -> {
                    if (p.tickCount % 2 != 0 || !mc.options.keyJump.isDown) return@on
                    val pitch = p.xRot
                    p.xRot = 82.0f
                    val bucket = Inventory.hotbarFind(Items.WATER_BUCKET)
                    if (bucket != null) {
                        Inventory.saveAndSelect(bucket.slot)
                        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
                        // 26.3 has no swing packet: this only animates.
                        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                        Inventory.restore()
                    }
                    p.xRot = pitch
                }
                else -> {}
            }
        }
        on<PreSyncEvent> {
            val p = mc.player ?: return@on
            when (mode.value) {
                Mode.MATRIX_2 -> {
                    if (!p.horizontalCollision || !mc.options.keyJump.isDown) return@on
                    if (p.deltaMovement.y() > -0.3739040364667221) return@on
                    p.setOnGround(true)
                    p.setDeltaMovement(p.deltaMovement.x(), 0.48114514191918, p.deltaMovement.z())
                }
                Mode.FT_FENCE -> {
                    if (!p.horizontalCollision || !Movement.moving()) return@on
                    p.setOnGround(true)
                    p.jumpFromGround()
                }
                else -> {}
            }
        }
    }
}
