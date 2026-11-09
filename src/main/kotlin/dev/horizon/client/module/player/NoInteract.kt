package dev.horizon.client.module.player

import dev.horizon.client.combat.Placer
import dev.horizon.client.event.InteractBlockEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.RedStoneOreBlock
import net.minecraft.world.level.block.TargetBlock
import net.minecraft.world.level.block.TrapDoorBlock

/**
 * Block clicks on containers, doors, buttons and the like (every block with
 * [allBlocks]), and any block click with a player head in that hand, are dropped, so vanilla uses the held item instead.
 * Offhand reads whether it's on.
 */
class NoInteract : Module("no-interact", Categories.PLAYER) {
    val allBlocks = bool("all-blocks", false)

    init {
        on<InteractBlockEvent> { e ->
            val level = mc.level ?: return@on
            val p = mc.player ?: return@on
            val block = level.getBlockState(e.hitResult.blockPos).block
            if (allBlocks.value || Placer.isInteractable(block) || block is FenceBlock || block is FenceGateBlock || block is TrapDoorBlock ||
                block is DoorBlock || block is TargetBlock || block is RedStoneOreBlock) e.cancel()
            if ((p.offhandItem.item == Items.PLAYER_HEAD && e.hand == InteractionHand.OFF_HAND) ||
                (p.mainHandItem.item == Items.PLAYER_HEAD && e.hand == InteractionHand.MAIN_HAND)) e.cancel()
        }
    }
}
