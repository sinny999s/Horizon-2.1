package dev.horizon.client.combat

import dev.horizon.client.module.legit.Teams
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.state.properties.BedPart

/**
 * Not in the original: whose bed is whose, for Bedwars (Bed Plates, Bed Breaker). A bed is yours when
 * its colour is your team's (from your dyed armour, else your name / scoreboard team colour). Two things make that
 * unreliable, and are handled:
 * - A server that shows every bed in one colour (a 1.8 server translated for new clients can): when two or more beds
 *   are known and all share one colour, colour says nothing, and a bed is only yours once the server has said so.
 * - Close shades (aqua armour against a cyan or light blue bed): your colour is matched to the nearest colour among the
 *   beds that are actually there, and only if it's close.
 * The server saying so: hitting your own bed gets a "can't break your own bed" kind of message, after which that bed
 * is yours for the rest of the world ([learnOwn]). So does standing by it when a match starts ([Bedwars]).
 */
object BedTeams {
    enum class Owner { OWN, ENEMY, UNKNOWN }

    class Bed(val head: BlockPos, val foot: BlockPos, val color: DyeColor) {
        fun contains(pos: BlockPos) = pos == head || pos == foot
    }

    private val mc: Minecraft get() = Minecraft.getInstance()

    /** Beds' heads the server said are yours, for this world. */
    private val learned = HashSet<BlockPos>()
    private var learnedLevel: Level? = null
    /** Bed colours seen in the last wide look, to tell whether colour means anything here. */
    private var seenColors: Map<DyeColor, Int> = emptyMap()
    private var seenLevel: Level? = null

    /** Every bed's head part in loaded chunks within [range] of [p]. Also remembers their colours for [owner]. */
    fun scan(p: Player, level: Level, range: Int): List<Bed> {
        val out = ArrayList<Bed>()
        val origin = p.blockPosition()
        val cx = origin.x shr 4
        val cz = origin.z shr 4
        val cr = (range shr 4) + 1
        for (dx in -cr..cr) for (dz in -cr..cr) {
            val chunk = level.chunkSource.getChunkNow(cx + dx, cz + dz) ?: continue
            val x0 = chunk.pos.x() shl 4
            val z0 = chunk.pos.z() shl 4
            val sections = chunk.sections
            for (i in sections.indices) {
                val sec = sections[i]
                if (sec.hasOnlyAir() || !sec.states.maybeHas { it.block is BedBlock }) continue
                val y0 = level.getSectionYFromSectionIndex(i) shl 4
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    val st = sec.getBlockState(x, y, z)
                    val b = st.block as? BedBlock ?: continue
                    if (st.getValue(BedBlock.PART) != BedPart.HEAD) continue
                    val pos = BlockPos(x0 + x, y0 + y, z0 + z)
                    if (pos.distSqr(origin) > range.toDouble() * range) continue
                    out += Bed(pos, pos.relative(BedBlock.getConnectedDirection(st)), b.color)
                }
            }
        }
        // Only a look over a good part of the map says which colours are around.
        if (range >= 48 || seenLevel !== level) {
            seenColors = out.groupingBy { it.color }.eachCount()
            seenLevel = level
        }
        return out
    }

    /** The bed at [pos] (either half), or null. */
    fun bedAt(level: Level, pos: BlockPos): Bed? {
        val st = level.getBlockState(pos)
        val b = st.block as? BedBlock ?: return null
        val other = pos.relative(BedBlock.getConnectedDirection(st))
        val head = if (st.getValue(BedBlock.PART) == BedPart.HEAD) pos else other
        val foot = if (head == pos) other else pos
        return Bed(head, foot, b.color)
    }

    fun owner(bed: Bed): Owner {
        val p = mc.player ?: return Owner.UNKNOWN
        checkLevel()
        if (bed.head in learned || bed.foot in learned) return Owner.OWN
        // A team has one bed: once yours is known, every other is someone else's.
        if (learned.isNotEmpty()) return Owner.ENEMY
        // Every bed one colour: colour isn't the team's.
        if (seenColors.values.sum() >= 2 && seenColors.size == 1) return Owner.UNKNOWN
        val mine = myRgb(p) ?: return Owner.UNKNOWN
        val colors = (seenColors.keys + bed.color).toSet()
        val nearest = colors.minBy { d -> distTo(d, mine) }
        return if (nearest == bed.color && distTo(bed.color, mine) <= CLOSE) Owner.OWN else Owner.ENEMY
    }

    /** The bed at [pos] is yours (the server refused to break it, or you started the match by it). Whether it's news. */
    fun learnOwn(pos: BlockPos): Boolean {
        checkLevel()
        val level = mc.level ?: return false
        val bed = bedAt(level, pos) ?: return false
        learned.clear()
        return learned.add(bed.head)
    }

    private fun checkLevel() {
        val level = mc.level
        if (level !== learnedLevel) {
            learned.clear()
            learnedLevel = level
        }
    }

    /** Your team's colour as RGB: dyed armour first (what Bedwars dresses teams in), then the name colours. */
    fun myRgb(p: Player): Int? {
        for (slot in SLOTS) {
            val rgb = p.getItemBySlot(slot).get(DataComponents.DYED_COLOR)?.rgb() ?: continue
            if (rgb and 0xFFFFFF == DEFAULT_LEATHER) continue
            return rgb and 0xFFFFFF
        }
        return Teams.resolveColor(p)
    }

    /** The dye nearest your team's colour, or null when it isn't known. */
    fun myColor(p: Player): DyeColor? = myRgb(p)?.let { rgb -> DyeColor.entries.minBy { distTo(it, rgb) } }

    /** The team name a bed's colour stands for, as Bedwars names them. */
    fun name(c: DyeColor): String = when (c) {
        DyeColor.LIME -> "Green"
        DyeColor.LIGHT_BLUE, DyeColor.CYAN -> "Aqua"
        DyeColor.LIGHT_GRAY -> "Light Gray"
        else -> c.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun distTo(d: DyeColor, rgb: Int) = shades(d).minOf { dist(it, rgb) }

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

    /** How close (squared RGB distance) your colour must be to a bed's: aqua 55FFFF to cyan 00FFFF is 7225. */
    private const val CLOSE = 8000
    private const val DEFAULT_LEATHER = 0xA06540
    private val CHAT = mapOf(DyeColor.RED to 0xFF5555, DyeColor.BLUE to 0x5555FF, DyeColor.LIME to 0x55FF55,
        DyeColor.GREEN to 0x00AA00, DyeColor.YELLOW to 0xFFFF55, DyeColor.CYAN to 0x00AAAA,
        DyeColor.LIGHT_BLUE to 0x55FFFF, DyeColor.PINK to 0xFF55FF, DyeColor.MAGENTA to 0xAA00AA,
        DyeColor.GRAY to 0x555555, DyeColor.LIGHT_GRAY to 0xAAAAAA, DyeColor.WHITE to 0xFFFFFF, DyeColor.ORANGE to 0xFFAA00)
    private val SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
}
