package dev.horizon.client.module.world

import dev.horizon.client.combat.FarmUtil
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Switch
import dev.horizon.client.setting.GroupMode
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock

/**
 * Every `delay` ticks, bone-meals the closest unripe selected crop within reach.
 *
 * Every selected block is read as a CropBlock for its age; melon and pumpkin stems aren't crops, so with those selected a
 * stem in reach ends the tick there (on purpose).
 */
class AutoBoneMeal : Module("auto-bone-meal", Categories.WORLD) {
    val switch = enum("switch", Switch.SILENT_FULL)
    val delay = int("delay", 0, 0..10)
    val selection = group("selection", GroupMode.BOOLS)
    val wheat = bool("wheat", true, parent = selection.value)
    val carrot = bool("carrot", true, parent = selection.value)
    val potato = bool("potato", true, parent = selection.value)
    val melon = bool("melon", false, parent = selection.value)
    val pumpkin = bool("pumpkin", false, parent = selection.value)
    val beetroot = bool("beetroot", false, parent = selection.value)

    private val timer = TickTimer()

    init {
        on<PlayerUpdateEvent> {
            if (!timer.passed(delay.value)) return@on
            timer.reset()
            val level = mc.level ?: return@on
            val crops = crops()
            var thrown = false
            val hits = FarmUtil.topHits(0.4375) { pos ->
                if (thrown) return@topHits false
                val state = level.getBlockState(pos)
                if (!crops.contains(state.block) || !FarmUtil.inReach(pos, 0.4375)) return@topHits false
                val crop = state.block as? CropBlock
                if (crop == null) {
                    thrown = true
                    return@topHits false
                }
                crop.getAge(state) != crop.maxAge
            }
            if (thrown) return@on
            FarmUtil.useOn(hits, switch.value) { it.item == Items.BONE_MEAL }
        }
    }

    /** The selected blocks, in this order. */
    private fun crops(): List<Block> {
        val l = ArrayList<Block>()
        if (wheat.value) l += Blocks.WHEAT
        if (carrot.value) l += Blocks.CARROTS
        if (potato.value) l += Blocks.POTATOES
        if (pumpkin.value) l += Blocks.PUMPKIN_STEM
        if (melon.value) l += Blocks.MELON_STEM
        if (beetroot.value) l += Blocks.BEETROOTS
        return l
    }
}
