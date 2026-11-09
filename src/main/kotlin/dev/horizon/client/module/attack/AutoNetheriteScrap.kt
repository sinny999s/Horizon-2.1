package dev.horizon.client.module.attack

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.combat.containerClick
import dev.horizon.client.combat.moduleChat
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Interact
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.PlaceMode
import dev.horizon.client.module.Switch2
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.setting.Bind
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * Uses a netherite scrap trap on [placeNormal] or [placeWithRegion] (with a
 * 50 s cooldown and optional 1.5-block target range check) and places a protection region block above the player's
 * head when inside an obsidian trap.
 */
class AutoNetheriteScrap : Module("auto-netherite-scrap", Categories.ATTACK) {
    val placeWithRegion = bind("place-with-region", Bind())
    val placeNormal = bind("place-normal", Bind())
    val privateOthers = bool("private-others", true)
    val checkRange = bool("check-range", false)

    private val scrapTimer = MsTimer()
    private val regionTimer = MsTimer()
    private var waitingForRegion = false

    private val regionBlocks = listOf(
        Blocks.COAL_BLOCK,
        Blocks.DIAMOND_ORE,
        Blocks.DIAMOND_BLOCK,
        Blocks.EMERALD_ORE,
        Blocks.ANCIENT_DEBRIS,
    )

    init {
        on<InputEvent> { e ->
            if (e.key != placeWithRegion.value.key && e.key != placeNormal.value.key) return@on
            if (e.action != InputEvent.Action.Press || mc.gui.screen() != null) return@on
            if (!scrapTimer.passed(50000L)) {
                moduleChat(this, "Wait trap cooldown!")
                return@on
            }
            if (checkRange.value && TargetFinder.find(1.5, TargetFinder.Sort.DISTANCE, false, false) == null) {
                moduleChat(this, "The target is too far away!")
                return@on
            }
            if (e.key == placeWithRegion.value.key) useWithRegion()
            if (e.key == placeNormal.value.key) useScrap()
        }

        on<PlayerUpdateEvent> {
            val pl = mc.player ?: return@on
            val level = mc.level ?: return@on
            if ((!waitingForRegion && !privateOthers.value) || !regionTimer.passed(15000L)) return@on
            val pos = BlockPos.containing(pl.eyePosition).above()
            if (level.getBlockState(pos.above()).block != Blocks.OBSIDIAN) return@on
            val slot = findRegionBlockSlot()
            if (slot == -1 || hasRegionBlockNearby()) return@on
            val req = Placer.place(pos, slot, Interact.Strict, PlaceMode.Normal, IgnoreMode.NONE, 4.0f, 0.0f, 200, Switch2.Alternative)
                ?: return@on
            RotationManager.queue(req)
            regionTimer.reset()
            waitingForRegion = false
        }
    }

    private fun useWithRegion() {
        useScrap()
        waitingForRegion = true
    }

    private fun useScrap() {
        val pl = mc.player ?: return
        val hot = Inventory.hotbarFind(Items.NETHERITE_SCRAP)
        val inv = Inventory.find(Items.NETHERITE_SCRAP)
        val hand = if (pl.offhandItem.item == Items.NETHERITE_SCRAP) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        if (hot != null) {
            Inventory.saveAndSelect(hot.slot)
            mc.gameMode?.useItem(pl, hand)
            Inventory.restore()
            scrapTimer.reset()
        } else if (inv != null) {
            val guiMove = Modules.get<GuiMove>()
            guiMove.internalClick = true
            containerClick(inv.slot, pl.inventory.selectedSlot, ContainerInput.SWAP)
            mc.gameMode?.useItem(pl, hand)
            containerClick(inv.slot, pl.inventory.selectedSlot, ContainerInput.SWAP, true)
            guiMove.internalClick = false
            scrapTimer.reset()
        }
    }

    private fun findRegionBlockSlot(): Int =
        regionBlocks.firstNotNullOfOrNull { block -> Inventory.find { it.`is`(block.asItem()) } }?.slot ?: -1

    private fun hasRegionBlockNearby(): Boolean {
        val pl = mc.player ?: return false
        val level = mc.level ?: return false
        val center = BlockPos.containing(pl.position())
        for (x in -5..5) for (y in -5..5) for (z in -5..5) {
            if (level.getBlockState(center.offset(x, y, z)).block in regionBlocks) return true
        }
        return false
    }
}
