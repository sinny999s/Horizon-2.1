package dev.horizon.client.module.world

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Automatically paves roads (1 to 5 blocks wide) beneath the player as you walk or fly.
 * Essential for paving Nether roof highways and tunnels on 6b6t with obsidian, netherrack, or blue ice.
 */
class HighwayBuilder : Module("highway-builder", Categories.WORLD) {
    enum class Switch { SILENT, NORMAL }

    val width = int("width", 3, 1..5)
    val delayMs = int("delay-ms", 50, 0..500)
    val switch = enum("switch", Switch.SILENT)
    val rotate = bool("rotate", false)
    val blocks = items(
        "blocks",
        arrayListOf(Items.OBSIDIAN, Items.NETHERRACK, Items.BLUE_ICE, Items.COBBLESTONE, Items.STONE)
    )

    private val timer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (!timer.passed(delayMs.value.toLong())) return@on

            // Find building block in hotbar
            val found = Inventory.hotbarFind { it.item is BlockItem && blocks.value.contains(it.item) } ?: return@on

            val groundY = floor(player.y - 0.5).toInt()
            val px = player.blockX
            val pz = player.blockZ
            val facing = player.direction
            val isNorthSouth = facing == Direction.NORTH || facing == Direction.SOUTH

            val halfWidth = (width.value - 1) / 2
            val candidatePositions = ArrayList<BlockPos>()

            // Add center and lateral positions
            for (offset in -halfWidth..halfWidth) {
                val bx = if (isNorthSouth) px + offset else px
                val bz = if (isNorthSouth) pz else pz + offset
                val pos = BlockPos(bx, groundY, bz)
                if (level.getBlockState(pos).canBeReplaced()) {
                    candidatePositions += pos
                }
            }

            if (candidatePositions.isEmpty()) return@on

            for (pos in candidatePositions) {
                // Find neighbor face to place against
                var neighbor: BlockPos? = null
                var side: Direction? = null
                for (d in Direction.entries) {
                    val n = pos.relative(d)
                    val nState = level.getBlockState(n)
                    if (!nState.canBeReplaced() && !nState.isAir) {
                        neighbor = n
                        side = d.opposite
                        break
                    }
                }

                // If no solid neighbor found, support from bottom
                val targetNeighbor = neighbor ?: pos.below()
                val targetSide = side ?: Direction.UP
                val clickPos = Vec3.atCenterOf(targetNeighbor).add(
                    targetSide.stepX * 0.5,
                    targetSide.stepY * 0.5,
                    targetSide.stepZ * 0.5
                )
                val hit = BlockHitResult(clickPos, targetSide, targetNeighbor, false)

                if (rotate.value) {
                    RotationManager.queue(RotationRequest(Rotation.lookAt(clickPos), 100) {})
                }

                var prevSlot = -1
                if (player.inventory.selectedSlot != found.slot) {
                    prevSlot = player.inventory.selectedSlot
                    Inventory.selectHotbar(found.slot)
                }

                Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)

                if (prevSlot != -1 && switch.value == Switch.SILENT) {
                    Inventory.selectHotbar(prevSlot)
                }

                timer.reset()
                break // place one per delay tick
            }
        }
    }
}
