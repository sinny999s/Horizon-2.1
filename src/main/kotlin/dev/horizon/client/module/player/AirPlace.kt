package dev.horizon.client.module.player

import dev.horizon.client.combat.Packets
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import java.awt.Color

/**
 * While the use key is held with a block in hand and the crosshair ray (Range long)
 * ends in air, places the block there, at most every 4 ticks (every tick with No Delay's blocks / all). 2b2t wraps
 * the place in an offhand swap (scaffold reads [n2b2t] for the same trick). Draws the target block.
 */
class AirPlace : Module("air-place", Categories.PLAYER) {
    val n2b2t = bool("2b2t", true)
    val range = float("range", 5.0f, 0.0f..6.0f)
    val fillColor = color("fill--color", Color(0x326432FF.toInt(), true))

    /** The last ray end, written every tick and never cleared. */
    private var target: BlockHitResult? = null

    /** Ticks until the next place. */
    private var cooldown = 0

    init {
        on<PlayerUpdateEvent> {
            if (cooldown > 0) cooldown--
            val hit = mc.cameraEntity!!.pick(range.value.toDouble(), 0.0f, false) as? BlockHitResult ?: return@on
            target = hit
            val level = mc.level ?: return@on
            val p = mc.player ?: return@on
            val gm = mc.gameMode ?: return@on
            if (level.getBlockState(hit.blockPos).block != Blocks.AIR) return@on
            val mainOk = p.mainHandItem.item is BlockItem
            val offOk = p.offhandItem.item is BlockItem
            if (!mc.options.keyUse.isDown) return@on
            if (!mainOk && !offOk) return@on
            if (cooldown > 0) return@on
            val hand = if (mainOk) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            // 26.3 has no swing packet (the server swings on a successful use): each swing is the local animation.
            val swing = p.getItemInHand(hand).interactAnimation
            if (n2b2t.value) {
                gm.useItem(p, hand)
                Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
                gm.useItemOn(p, hand, hit)
                p.swing(hand, swing, false)
                Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
                p.swing(hand, swing, false)
            } else {
                gm.useItemOn(p, hand, hit)
                p.swing(hand, swing, false)
            }
            val noDelay = Modules.get<NoDelay>()
            cooldown = if (noDelay.enabled && (noDelay.blocksSetting.value || noDelay.all.value)) 0 else 4
        }
        // The last tick's target, while it is still air and a block is held.
        on<Render3DEvent> {
            val hit = target ?: return@on
            val level = mc.level ?: return@on
            val p = mc.player ?: return@on
            if (level.getBlockState(hit.blockPos).block != Blocks.AIR) return@on
            if (p.mainHandItem.item !is BlockItem && p.offhandItem.item !is BlockItem) return@on
            val box = AABB(hit.blockPos)
            val c = fillColor.value.rgb
            World3D.fill(box, c)
            World3D.outline(box, Colors.withAlpha(c, 1.0f))
        }
    }
}
