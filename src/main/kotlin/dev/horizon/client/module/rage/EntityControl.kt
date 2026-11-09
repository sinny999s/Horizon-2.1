package dev.horizon.client.module.rage

import dev.horizon.client.combat.Movement
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.entity.PlayerRideableJumping
import net.minecraft.world.phys.Vec3

/**
 * Gives full movement, steering, and jump control over ridden entities (horses, donkeys, pigs,
 * striders, boats) even without a saddle or carrot-on-a-stick, with customizable jump height and speed.
 */
class EntityControl : Module("entity-control", Categories.RAGE) {
    val noSaddle = bool("no-saddle", true)
    val maxJump = bool("max-jump", true)
    val speed = float("speed", 0.6f, 0.1f..2.5f)
    val jumpPower = float("jump-power", 0.7f, 0.2f..1.5f)

    init {
        on<TravelEvent> { e ->
            if (!e.pre || !noSaddle.value) return@on
            val player = mc.player ?: return@on
            val vehicle = player.vehicle ?: return@on

            // Align vehicle yaw with player
            vehicle.yRot = player.yRot
            vehicle.yHeadRot = player.yRot

            if (Movement.moving()) {
                val move = Movement.strafe(speed.value)
                var vy = vehicle.deltaMovement.y
                if (mc.options.keyJump.isDown && vehicle.onGround()) {
                    vy = jumpPower.value.toDouble()
                }
                vehicle.deltaMovement = Vec3(move[0], vy, move[1])
                e.cancel()
            } else if (vehicle.onGround()) {
                vehicle.deltaMovement = Vec3(0.0, vehicle.deltaMovement.y, 0.0)
            }
        }

        on<PlayerUpdateEvent> {
            if (!maxJump.value) return@on
            val player = mc.player ?: return@on
            val vehicle = player.vehicle ?: return@on
            if (vehicle is PlayerRideableJumping && mc.options.keyJump.isDown) {
                vehicle.onPlayerJump(100)
            }
        }
    }
}
