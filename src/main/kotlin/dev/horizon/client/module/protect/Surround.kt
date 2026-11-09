package dev.horizon.client.module.protect

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.event.WorldUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.PlacerModule
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.floor

/** Keeps blocks around (and under) the feet, re-placing the moment one goes. */
class Surround : PlacerModule("surround", Categories.PROTECT, 200, showRange = false) {
    enum class Center { OFF, DEFAULT, SMART }

    val yDisable = bool("y disable", true)
    val async = bool("async", true)
    val asyncDelay = int("async-delay", 0, 0..250) { async.value }
    val blocksSetting = blocks("blocks", blockList("block.minecraft.obsidian", "block.minecraft.ender_chest", "block.minecraft.netherite_block"))
    val center = enum("center", Center.OFF)
    val forceCenter = bool("force-center", false) { center.value != Center.OFF }

    /** A block-update or crystal reaction placed since the last tick: skip the next tick's placing. */
    private var skipTick = false
    private val asyncTimer = MsTimer()

    /** The last tick that placed something (async waits 1.5 pings after it, so it doesn't double-place). */
    private val tickTimer = MsTimer()

    override fun blocks(): List<Block> = blocksSetting.value

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            if (p.y - (floor(p.yo) + 0.9f) > 0 && yDisable.value) disable()
        }
        on<PlayerUpdateEvent> {
            if (mc.player == null) return@on
            if (skipTick) {
                skipTick = false
                return@on
            }
            if (placeList(positions(feet(), ArrayList()), false)) tickTimer.reset()
        }
        on<WorldUpdateEvent> {
            if (mc.player == null || !async.value) return@on
            if (!asyncTimer.passedAndReset(asyncDelay.value.toLong())) return@on
            if (!tickTimer.passed(ceil(PlayerState.ping * 1.5).toLong())) return@on
            placeList(positions(feet(), ArrayList()), true)
            skipTick = true
        }
        on<AfterReceivePacketEvent>(10) { e ->
            val p = mc.player ?: return@on
            if (!async.value) return@on
            val pk = e.packet as? ClientboundAddEntityPacket ?: return@on
            if (pk.type != EntityTypes.END_CRYSTAL) return@on
            val c = Vec3(pk.x, pk.y, pk.z)
            if (c.distanceToSqr(p.eyePosition) >= 9.0) return@on
            val list = positions(feet(), ArrayList())
            if (list.isEmpty()) return@on
            // No swing packet follows the attack; 26.3 doesn't have one.
            Packets.send(ServerboundAttackPacket(pk.id))
            placeList(list.sortedBy { Vec3.atCenterOf(it).distanceTo(c) }, true)
            skipTick = true
            e.cancel()
        }
    }

    override fun onEnable() {
        val p = mc.player
        if (p == null || mc.level == null || center.value == Center.OFF) {
            skipTick = false
            return
        }
        val b = BlockPos.containing(p.position())
        val x: Double
        val z: Double
        if (center.value == Center.DEFAULT) {
            x = b.x + 0.5
            z = b.z + 0.5
        } else {
            x = Mth.clamp(p.x, b.x + 0.301, b.x + 0.699)
            z = Mth.clamp(p.z, b.z + 0.301, b.z + 0.699)
        }
        p.setPos(x, p.position().y, z)
        if (forceCenter.value) Packets.send(ServerboundMovePlayerPacket.PosRot(x, p.y, z, p.yRot, p.xRot, p.onGround(), p.horizontalCollision))
        skipTick = false
    }

    /** The block the feet are in; standing on something taller than 0.8 counts as the block above. */
    private fun feet(): BlockPos {
        val p = mc.player!!
        val y = p.y
        val fy = if (y - floor(y) > 0.8) floor(y) + 1.0 else floor(y)
        return BlockPos.containing(p.x, fy, p.z)
    }

    private fun canPlace(pos: BlockPos) =
        Placer.place(pos, 0, interact.value, placeMode.value, IgnoreMode.ONLY_CRYSTALS, 6.0f, 6.0f, 200, switch.value) != null

    /**
     * The free blocks around [pos] (and under it), going around any block a living entity stands in,
     * with a support block first where one is needed; nearest to where the movement keys are taking the player first.
     */
    private fun positions(pos: BlockPos, visited: MutableList<BlockPos>): List<BlockPos> {
        val level = mc.level ?: return emptyList()
        val p = mc.player ?: return emptyList()
        val result = ArrayList<BlockPos>()
        for (d in DIRS) {
            val n = pos.relative(d)
            if (n in visited) continue
            if (!level.getBlockState(n).canBeReplaced()) continue
            if (Placer.occupied(n)) {
                visited += pos
                result += positions(n, visited)
                continue
            }
            if (canPlace(n)) {
                result += n
                continue
            }
            // Every placeable support is added, and n after each of them (on purpose).
            for (d2 in DIRS) {
                val h = n.relative(d2)
                if (canPlace(h)) {
                    result += h
                    result += n
                }
            }
        }
        val off = PlayerState.movementOffset()
        val tx = p.x + off[0]
        val ty = p.y
        val tz = p.z + off[1]
        return result.sortedBy { Vec3.atCenterOf(it).distanceToSqr(tx, ty, tz) }
    }

    private companion object {
        val DIRS = listOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}
