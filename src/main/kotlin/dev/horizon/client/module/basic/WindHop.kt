package dev.horizon.client.module.basic

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.Slots
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Holds jump, and throws a wind charge down when a block is just below in the air, or at
 * the bottom of each solid wall block next to the player when walking into one.
 */
class WindHop : Module("wind-hop", Categories.BASIC) {
    val switch = enum("switch", Switch.SILENT_FULL)

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            mc.options.keyJump.isDown = true
            if (p.cooldowns.isOnCooldown(Items.WIND_CHARGE.defaultInstance)) return@on
            if (!p.onGround() && level.getBlockCollisions(p, p.boundingBox.move(0.0, -1.0, 0.0)).iterator().hasNext()) {
                throwAt(Rotation(p.yRot, 90.0f))
                return@on
            }
            if (!p.horizontalCollision) return@on
            val feet = BlockPos.containing(p.position())
            for (dir in Direction.entries) {
                if (dir == Direction.UP || dir == Direction.DOWN) continue
                val pos = feet.relative(dir)
                if (level.getBlockState(pos).canBeReplaced()) continue
                throwAt(Rotation.lookAt(Vec3.atCenterOf(pos).add(0.0, -0.5, 0.0)))
            }
        }
    }

    /** With a wind charge to hand, look there and throw it once the server has the rotation. */
    private fun throwAt(rotation: Rotation) {
        val found = Slots.find(switch.value) { it.`is`(Items.WIND_CHARGE) } ?: return
        RotationManager.queue(RotationRequest(rotation, 150) {
            // No swing packet after the throw: 26.3 has none.
            Slots.withSwitch(found.slot, switch.value) { mc.gameMode!!.useItem(mc.player!!, InteractionHand.MAIN_HAND) }
        })
    }

    override fun onDisable() {
        if (!PlayerState.physical(mc.options.keyJump)) mc.options.keyJump.isDown = false
    }
}
