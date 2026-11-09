package dev.horizon.client.module.player

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult

/**
 * While attack and use are both held, it mines the crosshair block or attacks the
 * crosshair entity (cooldown above 0.9) itself, straight through the game mode, so vanilla's "using an item" and
 * "hands busy" gates never apply.
 */
class MultiTask : Module("multi-task", Categories.PLAYER) {
    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val gm = mc.gameMode ?: return@on
            val level = mc.level ?: return@on
            val hit = mc.hitResult
            val o = mc.options
            // Any BlockHitResult counts, MISS included (on purpose).
            if (hit is BlockHitResult && o.keyAttack.isDown && o.keyUse.isDown && !level.getBlockState(hit.blockPos).isAir) {
                if (gm.isDestroying) gm.continueDestroyBlock(hit.blockPos, hit.direction) else gm.startDestroyBlock(hit.blockPos, hit.direction)
                // 26.3 has no swing packet to send with this swing.
                p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
            }
            if (mc.hitResult is EntityHitResult && o.keyAttack.isDown && o.keyUse.isDown && p.getAttackStrengthScale(0.5f) - 0.9f > 0) {
                gm.attack(p, (mc.hitResult as EntityHitResult).entity)
                p.swing(InteractionHand.MAIN_HAND, p.mainHandItem.attackAnimation, false)
            }
        }
    }
}
