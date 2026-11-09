package dev.horizon.client.module.player

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.animal.equine.Donkey
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.equine.Mule
import net.minecraft.world.entity.monster.Strider
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart

/**
 * Automatically mounts nearby rideable entities (horses, donkeys, boats, minecarts, etc.)
 * upon approach or keybind. Includes sneak bypass to allow effortless dismounting.
 */
class AutoMount : Module("auto-mount", Categories.PLAYER) {
    val range = float("range", 4.0f, 1.0f..6.0f)
    val delayMs = int("delay-ms", 500, 100..2000)
    val boats = bool("boats", true)
    val minecarts = bool("minecarts", true)
    val horses = bool("horses", true)
    val donkeys = bool("donkeys", true)
    val pigs = bool("pigs", false)
    val striders = bool("striders", false)
    val sneakBypass = bool("sneak-bypass", true)
    val rotate = bool("rotate", false)

    private val timer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on

            // If already riding or sneaking (with bypass on), don't remount
            if (player.isPassenger) return@on
            if (sneakBypass.value && player.isShiftKeyDown) return@on
            if (!timer.passed(delayMs.value.toLong())) return@on

            val eye = player.eyePosition
            var targetEntity: Entity? = null
            var closestDist = Double.MAX_VALUE

            for (entity in level.entitiesForRendering()) {
                if (!entity.isAlive || entity === player) continue
                if (entity.passengers.isNotEmpty()) continue // already has rider

                val valid = when (entity) {
                    is AbstractBoat -> boats.value
                    is AbstractMinecart -> minecarts.value
                    is Donkey, is Mule -> donkeys.value
                    is Horse, is AbstractHorse -> horses.value
                    is Pig -> pigs.value
                    is Strider -> striders.value
                    else -> false
                }
                if (!valid) continue

                val dist = eye.distanceTo(entity.position())
                if (dist <= range.value && dist < closestDist) {
                    closestDist = dist
                    targetEntity = entity
                }
            }

            val target = targetEntity ?: return@on

            if (rotate.value) {
                RotationManager.queue(RotationRequest(Rotation.lookAt(target.eyePosition), 120) {})
            }

            mc.gameMode?.interact(player, target, EntityHitResult(target), InteractionHand.MAIN_HAND)
            timer.reset()
        }
    }
}
