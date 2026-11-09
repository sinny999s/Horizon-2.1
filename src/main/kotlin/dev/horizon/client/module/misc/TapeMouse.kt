package dev.horizon.client.module.misc

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import kotlin.random.Random

/**
 * Clicks on whatever the crosshair is on every [delay] + up to [randomize] ms, checked
 * once per frame. Left attacks or starts breaking, right interacts or uses the held item on the block.
 */
class TapeMouse : Module("tape-mouse", Categories.MISC) {
    enum class Button { RIGHT, LEFT }

    val button = enum("button", Button.LEFT)
    val delay = int("delay", 600, 0..10000)
    val randomize = int("randomize", 50, 0..10000)

    private val timer = MsTimer()

    init {
        on<Render2DEvent> { click() }
    }

    private fun click() {
        if (!timer.passedAndReset((delay.value + (randomize.value * Random.nextDouble()).toInt()).toLong())) return
        val hit = mc.hitResult ?: return
        val player = mc.player!!
        val gameMode = mc.gameMode!!
        val level = mc.level!!
        if (button.value == Button.LEFT) {
            if (hit is EntityHitResult) {
                gameMode.attack(player, hit.entity)
            } else if (hit is BlockHitResult && !level.isEmptyBlock(hit.blockPos)) {
                gameMode.startDestroyBlock(hit.blockPos, hit.direction)
            }
            // 26.3's swing takes the animation; the held item's, as vanilla's own clicks use.
            player.swing(InteractionHand.MAIN_HAND, player.mainHandItem.attackAnimation, false)
            return
        }
        if (hit is EntityHitResult) {
            gameMode.interact(player, hit.entity, hit, InteractionHand.MAIN_HAND)
            return
        }
        if (hit is BlockHitResult && !level.isEmptyBlock(hit.blockPos)) {
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            player.swing(InteractionHand.MAIN_HAND, player.mainHandItem.interactAnimation, false)
        }
    }
}
