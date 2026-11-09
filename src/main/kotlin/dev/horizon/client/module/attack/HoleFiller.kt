package dev.horizon.client.module.attack

import dev.horizon.client.combat.HoleCache
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Predictor
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Fills the cached holes in range with obsidian (or cobwebs), optionally only the ones an
 * enemy stands next to or is about to reach.
 */
class HoleFiller : PlacerModule("hole-filler", Categories.ATTACK, 150) {
    enum class Mode { DEFAULT, AROUND_PLAYERS }
    enum class Prefer { OBBY, WEB }

    val mode = enum("mode", Mode.AROUND_PLAYERS)
    val prefer = enum("prefer", Prefer.OBBY)

    /** Cobwebs if there's one anywhere in the inventory (placing still only takes from the hotbar). */
    override fun blocks(): List<Block> =
        if (prefer.value == Prefer.WEB && Inventory.find(Items.COBWEB) != null) listOf(Blocks.COBWEB) else super.blocks()

    init {
        HoleCache.init()
        on<PlayerUpdateEvent> { placeList(holes(), false) }
    }

    /** Every cached hole cell in a cube of `range` around the eye block. */
    private fun holes(): List<BlockPos> {
        val self = mc.player ?: return emptyList()
        val c = BlockPos.containing(self.eyePosition)
        val r = ceil(range.value.toDouble()).toFloat().toInt()
        val out = ArrayList<BlockPos>()
        for (x in c.x - r..c.x + r) for (y in c.y - r..c.y + r) for (z in c.z - r..c.z + r) {
            val p = BlockPos(x, y, z)
            if (HoleCache.contains(p)) out += p
        }
        return if (mode.value == Mode.DEFAULT) out else aroundPlayers(out)
    }

    /**
     * Holes an enemy's (predicted) feet are within 2 blocks of the centre of, while level with or above
     * the hole's top. A hole is listed again for every player and prediction tick that matches.
     */
    private fun aroundPlayers(holes: List<BlockPos>): List<BlockPos> {
        val self = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val res = ArrayList<BlockPos>()
        for (hole in holes) {
            for (p in level.players()) {
                if (p === self) continue
                if (Friends.isFriend(p.gameProfile.name)) continue
                if (p.distanceTo(self) > 12.0f) continue
                if (p.y < hole.above().y.toDouble()) continue
                val centre = Vec3.atCenterOf(hole)
                val ticks = ceil(PlayerState.ping * 1.5 / 50.0).toInt()
                if (ticks <= 0) {
                    if (p.position().distanceTo(centre) <= 2.0) res += hole
                } else {
                    for (i in 0..ticks) if (Predictor.linear(p, i).distanceTo(centre) <= 2.0) res += hole
                }
            }
        }
        return res
    }
}
