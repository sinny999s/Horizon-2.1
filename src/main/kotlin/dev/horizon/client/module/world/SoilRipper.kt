package dev.horizon.client.module.world

import dev.horizon.client.combat.FarmUtil
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import net.minecraft.tags.ItemTags
import net.minecraft.world.level.block.Blocks

/**
 * Every `delay` ticks, tills the closest grass, dirt or path block with air above it
 * within reach. 26.3 has no HoeItem class, so hoes are found by the hoes tag.
 */
class SoilRipper : Module("soil-ripper", Categories.WORLD) {
    val switch = enum("switch", Switch.SILENT_FULL)
    val delay = int("delay", 0, 0..10)

    private val timer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            if (!timer.passed(delay.value)) return@on
            timer.reset()
            val level = mc.level ?: return@on
            val hits = FarmUtil.topHits(0.5) { pos ->
                val block = level.getBlockState(pos).block
                (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.DIRT_PATH) &&
                    level.getBlockState(pos.above()).block == Blocks.AIR && FarmUtil.inReach(pos, 0.5)
            }
            FarmUtil.useOn(hits, switch.value) { it.`is`(ItemTags.HOES) }
        }
    }
}
