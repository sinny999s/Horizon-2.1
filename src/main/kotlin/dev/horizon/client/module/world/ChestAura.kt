package dev.horizon.client.module.world

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Automatically opens nearby chests, trapped chests, barrels, and shulker boxes within reach.
 * Pairs with ChestStealer so you can loot entire stash rooms hands-free.
 */
class ChestAura : Module("chest-aura", Categories.WORLD) {
    val range = float("range", 4.5f, 2.0f..6.0f)
    val delayMs = int("delay-ms", 250, 50..1000)
    val chests = bool("chests", true)
    val shulkers = bool("shulkers", true)
    val enderChests = bool("ender-chests", false)
    val barrels = bool("barrels", true)
    val rotate = bool("rotate", true)
    val cooldownSeconds = int("cooldown-s", 4, 1..30)
    val render = bool("render", true)

    private val openTimer = MsTimer()
    private val openedMap = ConcurrentHashMap<BlockPos, Long>()
    private var targetPos: BlockPos? = null
    private val fade = Fade()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on

            // Don't open new chests if a screen/chest is already open
            if (mc.gui.screen() != null) return@on

            val now = System.currentTimeMillis()
            openedMap.entries.removeIf { now - it.value > cooldownSeconds.value * 1000L }

            if (!openTimer.passed(delayMs.value.toLong())) return@on

            val center = BlockPos.containing(player.eyePosition)
            val r = ceil(range.value).toInt()
            var closestPos: BlockPos? = null
            var closestDist = Double.MAX_VALUE

            for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
                val pos = BlockPos(x, y, z)
                if (openedMap.containsKey(pos)) continue

                val state = level.getBlockState(pos)
                val block = state.block
                val isChest = chests.value && (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST)
                val isShulker = shulkers.value && block.descriptionId.contains("shulker_box")
                val isEChest = enderChests.value && block == Blocks.ENDER_CHEST
                val isBarrel = barrels.value && block == Blocks.BARREL

                if (!isChest && !isShulker && !isEChest && !isBarrel) continue

                val hitCenter = Vec3.atCenterOf(pos)
                val d = hitCenter.distanceTo(player.eyePosition)
                if (d <= range.value && d < closestDist) {
                    closestDist = d
                    closestPos = pos
                }
            }

            targetPos = closestPos
            val pos = closestPos ?: return@on

            val hitPos = Vec3.atCenterOf(pos)
            val hit = BlockHitResult(hitPos, Direction.UP, pos, false)

            if (rotate.value) {
                RotationManager.queue(RotationRequest(Rotation.lookAt(hitPos), 120) {})
            }

            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            openedMap[pos] = now
            openTimer.reset()
            fade.restart(1f)
        }

        on<Render3DEvent> {
            val pos = targetPos ?: return@on
            if (!render.value) return@on
            fade.update(0f)
            if (fade.value <= 0.01f) return@on

            val box = AABB(pos)
            World3D.shaderBox(box, PlaceBoxes.withAlpha(Theme.primary, 0.35f * fade.value))
            World3D.outline(box, PlaceBoxes.withAlpha(Theme.primary, fade.value))
        }
    }

    override fun onDisable() {
        targetPos = null
        openedMap.clear()
    }

    class Fade {
        private var start = 0f
        private var target = 0f
        private var time = 0L
        var value = 0f
            private set

        fun restart(from: Float) {
            start = from
            value = from
            time = System.currentTimeMillis()
        }

        fun update(to: Float) {
            if (to != target) {
                start = value
                target = to
                time = System.currentTimeMillis()
            }
            val e = System.currentTimeMillis() - time
            value = if (e > 600) target else start + (target - start) * Easing.OUT_BACK.apply(e / 600.0).toFloat()
        }
    }
}
