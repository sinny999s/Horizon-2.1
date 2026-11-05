package dev.horizon.client.module.esp

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
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Not in the original: Bedwars bed plates. Over every bed in range, a plate with the bed's colour and the blocks
 * defending it, layer by layer outwards (wool, then wood, then end stone...), so you know what to bring before you rush.
 * Each layer is the blocks a step further from the bed (on top and to the sides; nothing under it), its block types
 * most-placed first. A layer with a gap straight to the bed is marked open. Only drawn on your screen.
 */
class BedPlates : Module("bed-plates", Categories.ESP, listOf("bed-esp", "bed-defense")) {
    val range = int("range", 96, 16..192)
    val layers = int("layers", 4, 1..6)
    /** Leave out the bed of your own team (matched by your dyed armour's colour). */
    val ownBed = bool("own-bed", false)
    val scale = float("scale", 1.0f, 0.5f..2.0f)

    private class Plate(val head: BlockPos, val foot: BlockPos, val color: DyeColor, val bed: ItemStack,
                        val blocks: List<ItemStack>, val open: Boolean)

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
            val mine = if (ownBed.value) null else myColor(p)
            plates = beds(p, lvl).filter { mine == null || it.second.color != mine }.map { (head, b) -> plate(lvl, head, b) }
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
                val label = if (pl.open) "open · ${dist}m" else "${dist}m"
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
                HudType.small.drawMiddle(label, x, h / 2f, if (pl.open) 0xFFFF5555.toInt() else Tok.T2)
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

    /** Every bed's head in loaded chunks within [range], with its block. */
    private fun beds(p: Player, lvl: ClientLevel): List<Pair<BlockPos, BedBlock>> {
        val out = ArrayList<Pair<BlockPos, BedBlock>>()
        val r = range.value
        val cx = p.blockPosition().x shr 4
        val cz = p.blockPosition().z shr 4
        val cr = (r shr 4) + 1
        for (dx in -cr..cr) for (dz in -cr..cr) {
            val chunk = lvl.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
            val x0 = chunk.pos.x() shl 4
            val z0 = chunk.pos.z() shl 4
            val sections = chunk.sections
            for (i in sections.indices) {
                val sec = sections[i]
                if (sec.hasOnlyAir() || !sec.states.maybeHas { it.block is BedBlock }) continue
                val y0 = lvl.getSectionYFromSectionIndex(i) shl 4
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    val st = sec.getBlockState(x, y, z)
                    val b = st.block as? BedBlock ?: continue
                    if (st.getValue(BedBlock.PART) != BedPart.HEAD) continue
                    val pos = BlockPos(x0 + x, y0 + y, z0 + z)
                    if (pos.distSqr(p.blockPosition()) > r.toDouble() * r) continue
                    out += pos to b
                }
            }
        }
        return out
    }

    private fun plate(lvl: ClientLevel, head: BlockPos, bed: BedBlock): Plate {
        val dir = BedBlock.getConnectedDirection(lvl.getBlockState(head))
        val foot = head.relative(dir)
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
        return Plate(head, foot, bed.color, ItemStack(bed.asItem()), blocks, open)
    }

    /** Your team's colour: the dye nearest your dyed leather armour's colour, or null without any. */
    private fun myColor(p: Player): DyeColor? {
        for (slot in SLOTS) {
            val rgb = p.getItemBySlot(slot).get(DataComponents.DYED_COLOR)?.rgb() ?: continue
            if (rgb and 0xFFFFFF == DEFAULT_LEATHER) continue
            return DyeColor.entries.minBy { d -> shades(d).minOf { dist(it, rgb) } }
        }
        return null
    }

    /**
     * The colours a server may dye a team's armour for [d]: the dye's own, its text and firework colours, and the chat
     * colour of the same team (Hypixel-style servers dye with those: red is FF5555, not the red dye's B02E26).
     */
    private fun shades(d: DyeColor): List<Int> = listOfNotNull(d.textureDiffuseColor and 0xFFFFFF, d.textColor and 0xFFFFFF,
        d.fireworkColor and 0xFFFFFF, CHAT[d])

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    private companion object {
        const val MAX_ICONS = 8
        const val DEFAULT_LEATHER = 0xA06540
        val CHAT = mapOf(DyeColor.RED to 0xFF5555, DyeColor.BLUE to 0x5555FF, DyeColor.LIME to 0x55FF55,
            DyeColor.GREEN to 0x00AA00, DyeColor.YELLOW to 0xFFFF55, DyeColor.CYAN to 0x00AAAA,
            DyeColor.LIGHT_BLUE to 0x55FFFF, DyeColor.PINK to 0xFF55FF, DyeColor.MAGENTA to 0xAA00AA,
            DyeColor.GRAY to 0x555555, DyeColor.LIGHT_GRAY to 0xAAAAAA, DyeColor.WHITE to 0xFFFFFF, DyeColor.ORANGE to 0xFFAA00)
        val SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    }
}
