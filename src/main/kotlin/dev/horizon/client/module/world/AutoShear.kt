package dev.horizon.client.module.world

import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.SwitchUtil
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.phys.EntityHitResult

/**
 * Every `delay` ticks, shears the closest grown, unsheared sheep whose eyes are within
 * entity reach (closest by its feet position).
 */
class AutoShear : Module("auto-shear", Categories.WORLD) {
    val switch = enum("switch", Switch.SILENT_FULL)
    val delay = int("delay", 0, 0..10)

    private val timer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            if (!timer.passed(delay.value)) return@on
            timer.reset()
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val sheep = level.entitiesForRendering().filter {
                it is Sheep && !it.isSheared && !it.isBaby && p.eyePosition.distanceTo(it.eyePosition) < p.entityInteractionRange()
            }.sortedBy { p.eyePosition.distanceToSqr(it.position()) }
            val slot = SwitchUtil.find(switch.value) { it.item is ShearsItem }
            if (sheep.isEmpty() || slot == -1) return@on
            val target = sheep.first()
            RotationManager.queue(InstantRequest(Rotation.lookAt(target.eyePosition), 0) {
                SwitchUtil.run(slot, switch.value) {
                    val pl = mc.player ?: return@run
                    mc.gameMode?.interact(pl, target, EntityHitResult(target, target.boundingBox.center), InteractionHand.MAIN_HAND)
                    // 26.3 has no swing packet: the swing is local only.
                    pl.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                }
            })
        }
    }
}
