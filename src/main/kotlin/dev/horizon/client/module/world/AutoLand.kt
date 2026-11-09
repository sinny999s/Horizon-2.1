package dev.horizon.client.module.world

import dev.horizon.client.combat.FarmUtil
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.Direction
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * Every `delay` ticks, plants one thing: the selected seeds on the closest free farmland;
 * failing that (sugar-cane) sugar cane next to water; failing that (nether-wart) nether wart on soul sand.
 */
class AutoLand : Module("auto-land", Categories.WORLD) {
    val switch = enum("switch", Switch.SILENT_FULL)
    val delay = int("delay", 0, 0..10)
    val selection = group("selection", GroupMode.BOOLS)
    val wheat = bool("wheat", true, parent = selection.value)
    val carrot = bool("carrot", true, parent = selection.value)
    val potato = bool("potato", true, parent = selection.value)
    val melon = bool("melon", false, parent = selection.value)
    val pumpkin = bool("pumpkin", false, parent = selection.value)
    val beetroot = bool("beetroot", false, parent = selection.value)
    val sugarCane = bool("sugar-cane", false, parent = selection.value)
    val netherWart = bool("nether-wart", false, parent = selection.value)

    private val timer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            if (!timer.passed(delay.value)) return@on
            timer.reset()
            if (seeds()) return@on
            if (sugarCane.value && sugarCane()) return@on
            if (netherWart.value) netherWart()
        }
    }

    /** Farmland with air above. */
    private fun seeds(): Boolean {
        val level = mc.level ?: return false
        val hits = FarmUtil.topHits(0.4375) { pos ->
            level.getBlockState(pos).block == Blocks.FARMLAND && FarmUtil.inReach(pos, 0.4375) && level.getBlockState(pos.above()).isAir
        }
        val items = seedItems()
        return FarmUtil.useOn(hits, switch.value) { items.contains(it.item) }
    }

    /** Sand, dirt, grass or red sand with water on a horizontal side and air above. */
    private fun sugarCane(): Boolean {
        val level = mc.level ?: return false
        val hits = FarmUtil.topHits(0.5) { pos ->
            val b = level.getBlockState(pos).block
            (b == Blocks.SAND || b == Blocks.DIRT || b == Blocks.GRASS_BLOCK || b == Blocks.RED_SAND) &&
                Direction.entries.filter { it != Direction.UP && it != Direction.DOWN }
                    .any { level.getBlockState(pos.relative(it)).block == Blocks.WATER } &&
                FarmUtil.inReach(pos, 0.5) && level.getBlockState(pos.above()).isAir
        }
        return FarmUtil.useOn(hits, switch.value) { it.item == Items.SUGAR_CANE }
    }

    /** Soul sand with air above. */
    private fun netherWart(): Boolean {
        val level = mc.level ?: return false
        val hits = FarmUtil.topHits(0.5) { pos ->
            level.getBlockState(pos).block == Blocks.SOUL_SAND && FarmUtil.inReach(pos, 0.5) && level.getBlockState(pos.above()).isAir
        }
        return FarmUtil.useOn(hits, switch.value) { it.item == Items.NETHER_WART }
    }

    /** The selected seeds, in this order. */
    private fun seedItems(): List<Item> {
        val l = ArrayList<Item>()
        if (wheat.value) l += Items.WHEAT_SEEDS
        if (carrot.value) l += Items.CARROT
        if (potato.value) l += Items.POTATO
        if (pumpkin.value) l += Items.PUMPKIN_SEEDS
        if (melon.value) l += Items.MELON_SEEDS
        if (beetroot.value) l += Items.BEETROOT_SEEDS
        return l
    }
}
