package dev.horizon.client.module.attack

import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Picks the block to mine on the enemy (burrow block, CEV obsidian over the head, or the
 * best city block, scored by the crystal spots it opens) and hands it to Speed Mine; frees us when crawling.
 */
class AutoMine : Module("auto-mine", Categories.ATTACK) {
    val cevPriority = bool("cev-priority", true)
    val antiShulker = bool("anti-shulker", true)
    val eChestsMine = bool("e-chests-mine", true)
    val debug = bool("debug", false)
    val antiCrawl = bool("anti-crawl", true)
    val levelDown = bind("level-down", Bind())
    val cevCrawlers = bool("cev-crawlers", false)
    val syncWithRebreak = bool("sync-with-rebreak", true)
    val holdSelection = group("hold-selection", GroupMode.BOOLS) { syncWithRebreak.value }
    val holdCiv = bool("hold-civ", true, parent = holdSelection.value) { syncWithRebreak.value }
    val holdCev = bool("hold-cev", true, parent = holdSelection.value) { syncWithRebreak.value }
    val holdSurr = bool("hold-surr", true, parent = holdSelection.value) { syncWithRebreak.value }

    /** A candidate block and its score (geometry only, no damage maths). */
    private data class Candidate(val pos: BlockPos, val score: Float)

    /** The hand-off gate; target logic waits 60 ticks after a hand-off. */
    private val gate = TickTimer()

    /** The last tick's candidates, drawn with `debug`. */
    private var debugList = ArrayList<Candidate>()

    init {
        on<PlayerUpdateEvent> { tick() }
        on<Render3DEvent> { if (debug.value) render() }
    }

    private val sm get() = Modules.get<SpeedMine>()

    private fun tick() {
        val p = mc.player ?: return
        debugList.clear()
        val myFeet = BlockPos.containing(p.position())
        if (antiCrawl.value && antiCrawl()) return
        if (eChestsMine.value) eChestMine()
        if (held(levelDown.value)) {
            mine(myFeet.below(), true)
            return
        }
        val target = Modules.get<AutoCrystal>().target
            ?: TargetFinder.find(p.blockInteractionRange().toFloat().toDouble(), TargetFinder.Sort.FOV, false, false)
            ?: return
        if (!gate.passed(60)) return
        if (antiShulker.value) antiShulker(target)
        val tFeet = BlockPos.containing(target.position())
        val corners = corners(target)
        val burrow = corners.filter { inRange(it) && it != myFeet && block(it) in OE }
        val cand = ArrayList<Candidate>()
        var airSides = 0
        if (block(tFeet) != Blocks.BEDROCK) {
            if (holdForRebreak(target, corners)) return
            cevCandidates(target, corners, cand, false)
            for (d in HORIZONTAL) {
                if (city(d, tFeet, myFeet, cand)) airSides++
            }
        }
        if (airSides > 1) cand.clear()
        for (b in burrow) cand += Candidate(b, 300.0f)
        debugList = cand
        select(cand)
    }

    /** Crawling under a block (not swimming) → mine the head block, else the one underneath. */
    private fun antiCrawl(): Boolean {
        val p = mc.player ?: return false
        val feet = BlockPos.containing(p.position())
        if (p.pose != Pose.SWIMMING) return false
        if (p.isInWater || p.isUnderWater) return false
        val up = feet.above()
        val dn = feet.below()
        if (block(up) != Blocks.BEDROCK && minable(up)) {
            mine(up, true)
            return true
        }
        if (block(dn) != Blocks.BEDROCK && minable(dn)) {
            mine(dn, true)
            return true
        }
        return false
    }

    private fun minable(pos: BlockPos): Boolean {
        val level = mc.level ?: return false
        val s = level.getBlockState(pos)
        return s.getDestroySpeed(level, pos) > 0 && s.block != Blocks.AIR && s.block != Blocks.CAVE_AIR && !s.canBeReplaced()
    }

    /** An ender chest within 2.2 of our eyes (anyone's, our own surround's too). */
    private fun eChestMine() {
        val p = mc.player ?: return
        for (be in ServerInfo.blockEntities) {
            if (p.eyePosition.distanceTo(Vec3.atCenterOf(be.blockPos)) <= 2.2 && be is EnderChestBlockEntity) {
                mine(be.blockPos, false)
                break
            }
        }
    }

    /** A shulker box within 2.2 of the target's eyes, forced. */
    private fun antiShulker(target: Player) {
        for (be in ServerInfo.blockEntities) {
            if (target.eyePosition.distanceTo(Vec3.atCenterOf(be.blockPos)) <= 2.2 && be is ShulkerBoxBlockEntity) {
                mine(be.blockPos, true)
                break
            }
        }
    }

    /** The bind is held (polled each tick); never with chat open. */
    private fun held(b: Bind): Boolean {
        if (b.key == -1 || mc.gui.screen() is ChatScreen) return false
        return if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)
    }

    /** The blocks under the four corners of the target's box, at its feet height (duplicates kept). */
    private fun corners(t: Player): List<BlockPos> {
        val bb = t.boundingBox
        return listOf(
            BlockPos.containing(bb.minX + 0.001, bb.minY, bb.minZ + 0.001),
            BlockPos.containing(bb.minX + 0.001, bb.minY, bb.maxZ - 0.001),
            BlockPos.containing(bb.maxX - 0.001, bb.minY, bb.maxZ - 0.001),
            BlockPos.containing(bb.maxX - 0.001, bb.minY, bb.minZ + 0.001),
        )
    }

    /** Leave Speed Mine on its rebreak block when that's a CEV/surround/CIV block. */
    private fun holdForRebreak(target: Player, corners: List<BlockPos>): Boolean {
        val level = mc.level ?: return false
        val sm = sm
        if (!syncWithRebreak.value || !sm.enabled || sm.rebreak.value == SpeedMine.Rebreak.OFF) return false
        val rp = sm.queue.firstOrNull { level.isEmptyBlock(it.pos) || it.breaks > 0 }?.pos ?: return false
        if (holdCev.value) {
            val tmp = ArrayList<Candidate>()
            cevCandidates(target, corners, tmp, true)
            if (tmp.any { level.getBlockState(it.pos.below()).isAir && it.pos == rp }) return true
        }
        for (c in corners) {
            for (d in HORIZONTAL) {
                val n = c.relative(d)
                if (n in corners) continue
                if (holdSurr.value && rp == n) return true
                if (holdCiv.value && rp == n.above()) return true
            }
        }
        return false
    }

    /** The obsidian two to five blocks above the target's corners (CEV). */
    private fun cevCandidates(target: Player, corners: List<BlockPos>, out: MutableList<Candidate>, force: Boolean) {
        val p = mc.player ?: return
        if (target.isVisuallyCrawling && !cevCrawlers.value) return
        val dm = sm.enabled && sm.doubleMine.value
        val hi = if (dm) 100.0f else 99.0f
        val lo = if (dm) 99.0f else 100.0f
        val range = p.blockInteractionRange()
        for (c in corners.sortedBy { target.eyePosition.distanceTo(Vec3.atCenterOf(it)) }) {
            val base = c.above(2)
            for (i in 0..3) {
                val pos = base.above(i)
                if (!(p.eyePosition.distanceTo(Vec3.atCenterOf(pos)) <= range)) continue
                if (!force && block(pos) != Blocks.OBSIDIAN) continue
                val aboveAir = state(pos.above()).isAir
                val belowAir = state(pos.below()).isAir
                if (!aboveAir && i == 3) break
                val score = if (cevPriority.value) (if (belowAir) hi else lo) else 0.5f
                out += Candidate(pos, score)
            }
        }
    }

    /**
     * One side of the target's feet block (city scoring): the surround block there, scored by the crystal
     * spots mining it would open, plus its solid second-layer neighbours as their own candidates. Returns whether the
     * side is open (exactly `Blocks.AIR`).
     */
    private fun city(d: Direction, tFeet: BlockPos, myFeet: BlockPos, cand: MutableList<Candidate>): Boolean {
        val s = tFeet.relative(d)
        val open = block(s) == Blocks.AIR
        if (block(s) !in OE || !inRange(s) || s == myFeet) return open
        var score = 0.0f
        if (block(s.below()) in OB) score += 6.0f
        for (i in 1..2) {
            if (block(s.below().relative(d, i)) in OB && state(s.relative(d, i)).isAir) score += 7.5f
        }
        for (d2 in HORIZONTAL) {
            if (d2 == d) continue
            if (block(s.relative(d2)) in OB) continue
            val c = s.relative(d).relative(d2)
            if (block(c.below()) in OB && state(c).isAir) score += 9.0f
        }
        for (d3 in NOT_DOWN) {
            val n = s.relative(d3)
            if (n == tFeet || n == myFeet || !inRange(n)) continue
            if (state(n).isAir) {
                if (block(n.below()) in OB) score += if (block(n.below(2)) in OB) 10.0f else 9.0f
                continue
            }
            if (block(n) !in OE) continue
            var first = true
            for (d4 in NOT_DOWN) {
                val m = n.relative(d4)
                if (m == tFeet || m == n || m == s || m == myFeet || !inRange(m)) continue
                if (!state(m).isAir || block(m.below()) !in OB) continue
                score += 1.0f
                if (first) {
                    val v = if (d3 == Direction.UP) 11.0f
                    else if (OUTER.any { d5 -> d5 != d3.opposite && block(n.below().relative(d5)) in OB && state(n.relative(d5)).isAir }) 16.0f
                    else 11.0f
                    cand += Candidate(n, v)
                    first = false
                }
            }
        }
        cand += Candidate(s, score)
        return open
    }

    /** The best candidate; with double-mine the 100-point CEV block goes second. */
    private fun select(cand: List<Candidate>) {
        val dm = sm.enabled && sm.doubleMine.value
        if (dm) {
            val pool = if (cand.any { it.score == 100.0f }) cand.filter { it.score != 100.0f } else cand
            val primary = pool.maxByOrNull { it.score }
            val secondary = cand.filter { primary != null && it != primary }.maxByOrNull { it.score }
            if (primary == null) return
            val pick = if (sm.inQueue(primary.pos)) secondary ?: primary else primary
            mine(pick.pos, false)
        } else {
            cand.maxByOrNull { it.score }?.let { mine(it.pos, false) }
        }
    }

    /** Hand the block to Speed Mine (vanilla mining without it). */
    private fun mine(pos: BlockPos, force: Boolean) {
        val p = mc.player ?: return
        val sm = sm
        if (sm.busy() && !force) return
        if (sm.enabled) {
            if (!sm.doubleMine.value || sm.queue.size == 1) gate.reset()
            sm.startMining(pos, Direction.UP)
            return
        }
        mc.gameMode?.continueDestroyBlock(pos, Direction.UP)
        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
    }

    /** Each candidate's outline and score, pulsing in the theme colour. */
    private fun render() {
        for (c in debugList) {
            val phase = (System.currentTimeMillis() / 2.0 % 360).toInt()
            val tri = if (phase < 180) phase else 360 - phase
            val col = Colors.lerp(Colors.withAlpha(Theme.primary, 0.5f), Colors.withAlpha(Theme.primary, 0.0f), tri / 360f)
            World3D.outline(AABB(c.pos), col)
            World3D.label(c.score.toString(), Vec3.atCenterOf(c.pos), col)
        }
    }

    private fun inRange(pos: BlockPos): Boolean {
        val p = mc.player ?: return false
        return p.eyePosition.distanceTo(Vec3.atCenterOf(pos)) <= p.blockInteractionRange()
    }

    private fun state(pos: BlockPos): BlockState = mc.level!!.getBlockState(pos)

    private fun block(pos: BlockPos): Block = state(pos).block

    private companion object {
        val OE = setOf(Blocks.OBSIDIAN, Blocks.ENDER_CHEST)
        val OB = setOf(Blocks.OBSIDIAN, Blocks.BEDROCK)
        val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        val NOT_DOWN = listOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        val OUTER = listOf(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)
    }
}
