package dev.horizon.client.module.esp

import dev.horizon.client.combat.BedTeams
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.Hud
import dev.horizon.client.hud.HudType
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bedwars bed plates. Over every bed in range, a plate with the bed's colour and the blocks
 * defending it, layer by layer outwards (wool, then wood, then end stone...), so you know what to bring before you rush.
 * Each layer is the blocks a step further from the bed (on top and to the sides; nothing under it), its block types
 * most-placed first. A layer with a gap straight to the bed is marked open, your own team's bed "yours" ([BedTeams] says
 * whose is whose). Only drawn on your screen.
 */
class BedPlates : Module("bed-plates", Categories.ESP, listOf("bed-esp", "bed-defense")) {
    val range = int("range", 96, 16..192)
    val layers = int("layers", 4, 1..6)
    /** Show your own team's bed too (marked "yours"); off leaves it out. Whose bed is whose: [BedTeams]. */
    val ownBed = bool("own-bed", false)
    val scale = float("scale", 1.0f, 0.5f..2.0f)

    private class Plate(val head: BlockPos, val foot: BlockPos, val bed: ItemStack, val blocks: List<ItemStack>,
                        val open: Boolean, val own: Boolean)

    @Volatile
    private var plates: List<Plate> = emptyList()
    private var ticks = 0

    override fun onEnable() {
        plates = emptyList()
        ticks = 0
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val lvl = mc.level ?: return@on
            // A full look twice a second: beds don't move, their defenses change about that fast.
            if (ticks++ % 10 != 0) return@on
            plates = BedTeams.scan(p, lvl, range.value).mapNotNull { b ->
                val own = BedTeams.owner(b) == BedTeams.Owner.OWN
                if (own && !ownBed.value) null else plate(lvl, b, own)
            }
        }

        on<Render2DEvent> {
            val p = mc.player ?: return@on
            val pose = Canvas.pose
            val g = Canvas.graphics ?: return@on
            // Far ones first, so near plates draw over them.
            for (pl in plates.sortedByDescending { it.head.distSqr(p.blockPosition()) }) {
                val c = Vec3((pl.head.x + pl.foot.x) / 2.0 + 0.5, pl.head.y + 1.3, (pl.head.z + pl.foot.z) / 2.0 + 0.5)
                val s = World3D.project(c) ?: continue
                val dist = sqrt(p.distanceToSqr(c)).toInt()
                val label = (if (pl.own) "yours · " else "") + (if (pl.open) "open · ${dist}m" else "${dist}m")
                val icons = pl.blocks.size
                val w = 17.5f + icons * 11f + (if (icons > 0) 3f else 0f) + HudType.small.width(label) + 4f
                val h = 13f
                pose.pushMatrix()
                pose.translate(s[0], s[1])
                pose.scale(scale.value, scale.value)
                pose.translate(-w / 2f, -h)
                Hud.plate(0f, 0f, w, h, 3.5f, 1f)
                Canvas.quad(17f, 2.25f, 0.5f, 8.5f, Colors.withAlpha(0x44FFFFFF, 0x44 / 255f))
                var x = 20.5f
                for (it in pl.blocks) {
                    item(g, it, x, 2.2f)
                    x += 11f
                }
                if (icons > 0) x += 3f
                HudType.small.drawMiddle(label, x, h / 2f, when {
                    pl.own -> 0xFF55FF55.toInt()
                    pl.open -> 0xFFFF5555.toInt()
                    else -> Tok.T2
                })
                item(g, pl.bed, 3.8f, 1.3f, 0.65f)
                pose.popMatrix()
            }
        }
    }

    private fun item(g: net.minecraft.client.gui.GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, s: Float = 0.55f) {
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(s, s)
        g.item(stack, 0, 0)
        pose.popMatrix()
    }

    private fun plate(lvl: ClientLevel, b: BedTeams.Bed, own: Boolean): Plate {
        val head = b.head
        val foot = b.foot
        val bedCells = listOf(head, foot)
        val n = layers.value
        // Each cell around the bed, by its step count from the nearer bed half (on top and to the sides only).
        val counts = Array(n + 1) { HashMap<Block, Int>() }
        var open = false
        val minX = minOf(head.x, foot.x) - n
        val maxX = maxOf(head.x, foot.x) + n
        val minZ = minOf(head.z, foot.z) - n
        val maxZ = maxOf(head.z, foot.z) + n
        val m = BlockPos.MutableBlockPos()
        for (x in minX..maxX) for (z in minZ..maxZ) for (y in head.y..head.y + n) {
            val d = bedCells.minOf { abs(it.x - x) + abs(it.z - z) + (y - it.y) }
            if (d < 1 || d > n) continue
            m.set(x, y, z)
            val st = lvl.getBlockState(m)
            if (st.isAir || st.canBeReplaced()) {
                // A gap in the first layer: the bed can be hit without breaking anything.
                if (d == 1) open = true
                continue
            }
            if (st.block is BedBlock) continue
            counts[d].merge(st.block, 1, Int::plus)
        }
        val order = LinkedHashSet<Block>()
        for (d in 1..n) for ((b, _) in counts[d].entries.sortedByDescending { it.value }) order += b
        val blocks = order.take(MAX_ICONS).map { ItemStack(it.asItem()) }.filter { !it.isEmpty }
        return Plate(head, foot, ItemStack(lvl.getBlockState(head).block.asItem()), blocks, open, own)
    }

    private companion object {
        const val MAX_ICONS = 8
    }
}
