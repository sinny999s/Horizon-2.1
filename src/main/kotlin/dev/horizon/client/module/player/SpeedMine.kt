package dev.horizon.client.module.player

import dev.horizon.client.combat.GrimRequest
import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlacementLock
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.event.AfterReceivePacketEvent
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.AttackBlockEvent
import dev.horizon.client.event.BlockBreakProgressEvent
import dev.horizon.client.event.CollisionEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.mixin.GameModeAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.AutoCrystal
import dev.horizon.client.module.equip.ToolSaver
import dev.horizon.client.module.world.FreeCam
import dev.horizon.client.render.Colors
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Packet mining. A click starts a block (START, then ABORT/STOP), progress is counted client-side for the best tool, and
 * once it's ready the tool is swapped in for the STOP. Up to two blocks with double-mine; rebreak modes for blocks that
 * get placed again. With [hold], holding the button mines each new block it moves onto too. With [maxBlocks],
 * double-mine lines up more and mines them two at a time: a server only tracks two per player, its delayed and
 * current destroy blocks, and starting a third would lose the current one, so the next two start once both are gone.
 */
class SpeedMine : Module("speed-mine", Categories.PLAYER) {
    enum class Mode { PACKET, DAMAGE }
    enum class Timing { VANILLA, SEQUENTIAL, GRIM }
    enum class Rebreak { OFF, GRIM, NCP_AIR }
    enum class PacketOrder { OLD, NEW }
    enum class StartMode { ABORT, STOP, NONE }
    @Suppress("EnumEntryName")
    enum class SwitchMode { Silent, Normal, Alternative }
    enum class SwapTiming { ONCE, ALWAYS }
    enum class OnEating { PAUSE, GRIM, RESET, NONE }
    @Suppress("EnumEntryName")
    enum class RenderMode { Block, Shrink, Grow }
    enum class Selection { ALWAYS, AIR_REBREAK, REBREAK, OFF }

    val mode = enum("mode", Mode.PACKET)
    val hold = bool("hold", true) { mode.value == Mode.PACKET }
    val timing = enum("timing", Timing.SEQUENTIAL) { mode.value == Mode.PACKET && rotate.value }
    val rebreak = enum("rebreak", Rebreak.OFF) { mode.value == Mode.PACKET }
    val awaitAutoCrystal = bool("await-auto-crystal", true) { mode.value == Mode.PACKET && rebreak.value == Rebreak.GRIM }
    val instantAsync = bool("instant-async", true) { mode.value == Mode.PACKET && rebreak.value == Rebreak.GRIM }
    val doubleMine = bool("double-mine", false) { mode.value == Mode.PACKET }
    val maxBlocks = int("max-blocks", 2, 2..6) { mode.value == Mode.PACKET && doubleMine.value }
    val packetOrder = enum("packet-order", PacketOrder.NEW) { mode.value == Mode.PACKET && doubleMine.value }
    val startMode = enum("start-mode", StartMode.ABORT) { mode.value == Mode.PACKET && !doubleMine.value }
    val switchMode = enum("switch-mode", SwitchMode.Alternative) { mode.value == Mode.PACKET }
    val n121 = bool("1.21", false) { mode.value == Mode.PACKET }
    val swapTiming = enum("swap-timing", SwapTiming.ONCE) { mode.value == Mode.PACKET }
    val factor = float("factor", 1.0f, 0.5f..2.0f) { mode.value == Mode.PACKET }
    val speed = float("speed", 0.5f, 0.0f..1.0f) { mode.value == Mode.DAMAGE }
    val rotate = bool("rotate", true) { mode.value == Mode.PACKET }
    val await = bool("await", true) { mode.value == Mode.PACKET }
    val phase = bool("phase", true) { mode.value == Mode.PACKET }
    val resetOnSwitch = bool("reset-on-switch", true) { mode.value == Mode.PACKET }
    val onEating = enum("on-eating", OnEating.NONE) { mode.value == Mode.PACKET }
    val render = group("render") { mode.value == Mode.PACKET }
    val renderMode = enum("render-mode", RenderMode.Shrink, parent = render.value) { mode.value == Mode.PACKET }
    val selection = enum("selection", Selection.AIR_REBREAK, parent = render.value) { mode.value == Mode.PACKET }
    val start = color("start", Color(0xC8FF0000.toInt(), true), parent = render.value) { mode.value == Mode.PACKET }
    val end = color("end", Color(0xC82FFF00.toInt(), true), parent = render.value) { mode.value == Mode.PACKET }

    enum class ResetReason { SWITCH, TOGGLE, EATING }

    /** One block in the mining queue; creating it sends the start sequence. */
    inner class Entry(val pos: BlockPos, dir: Direction?) {
        /** Progress (uncapped; 1 = ready). */
        var progress = 0f

        /** Last tick's progress; −1 while the block is air and held for a rebreak. */
        var prevProgress = 0f

        /** Break sequences sent. */
        var breaks = 0

        /** On-ground samples, one per tick while the block is air (NCP_AIR credits them). */
        private val samples = ArrayDeque<Boolean>()

        /** At most one restart per 500 ms. */
        private val restartTimer = MsTimer()

        init {
            restartTimer.reset()
            start(dir)
        }

        /** The start packets (with swap-timing ALWAYS, around a tool swap). */
        fun start(dir0: Direction?) {
            val p = mc.player ?: return
            val dir = dir0 ?: p.direction
            val prev = p.inventory.selectedSlot
            val slot = bestTool(pos)
            if (swapTiming.value == SwapTiming.ALWAYS) {
                if (slot == -1) return
                swap(slot, -1)
            }
            if (doubleMine.value) {
                if (packetOrder.value == PacketOrder.NEW) {
                    seq(START, pos, dir)
                    abortPacket(pos.offset(0, 500, 0))
                } else {
                    seq(STOP, pos, dir)
                    seq(START, pos, dir)
                }
                seq(STOP, pos, dir)
            } else {
                seq(START, pos, dir)
                when (startMode.value) {
                    StartMode.ABORT -> abortPacket(pos)
                    StartMode.STOP -> seq(STOP, pos, dir)
                    StartMode.NONE -> {}
                }
            }
            if (swapTiming.value == SwapTiming.ALWAYS) {
                toolSwapInFlight.set(true)
                if (n121.value) {
                    Scheduler.ticks(1) {
                        swap(prev, slot)
                        toolSwapInFlight.set(false)
                    }
                } else {
                    swap(prev, slot)
                }
            }
        }

        /** One tick of this block; true = drop it from the queue. */
        fun update(forced: Boolean): Boolean {
            val p = mc.player ?: return false
            val level = mc.level ?: return false
            val dir = Placer.visibleFaces(pos).firstOrNull() ?: p.direction
            val first = queue.firstOrNull { !level.isEmptyBlock(it.pos) }
            val ready = rebreakReady()
            val slot = bestTool(pos)
            if (breaks > 0 && doubleMine.value && pos != lastStarted) {
                abort()
                return true
            }
            if (!canMine(pos)) {
                abort()
                return true
            }
            if (level.isEmptyBlock(pos)) {
                progress = 0f
                prevProgress = -1f
                samples.addLast(p.onGround())
                return pos != lastStarted && queue.size > 1
            }
            if (progress == 0f && prevProgress == -1f && mode.value == Mode.PACKET && restartTimer.passedAndReset(500) &&
                !(breaks > 0 && rebreak.value == Rebreak.GRIM && (pos == lastStarted || queue.size <= 1))) {
                if (samples.isEmpty() || rebreak.value != Rebreak.NCP_AIR) {
                    start(dir)
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                } else {
                    for (s in samples) {
                        prevProgress = progress
                        progress += delta(level.getBlockState(pos), pos, slot, s)
                    }
                }
                return false
            }
            if (slot == -1) return false
            if (p.isUsingItem && onEating.value == OnEating.GRIM) {
                prevProgress = progress
                progress += delta(level.getBlockState(pos), pos, slot, p.onGround())
                return false
            }
            if (progress < 1 && !ready) {
                prevProgress = progress
                progress += delta(level.getBlockState(pos), pos, slot, p.onGround())
                return false
            }
            // Double-mine: the older block waits while both are still solid.
            if (first != null && first === this && queue.count { !level.isEmptyBlock(it.pos) } == 2) return false
            val state = level.getBlockState(pos)
            if (state.isAir || state.liquid()) return false
            if (recent.containsKey(pos) && await.value) return false
            if (rotate.value) {
                val rot = Rotation.lookAt(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0))
                // The slot to go back to is read when the action runs.
                val action = { if (!forced) breakBlock(slot, mc.player?.inventory?.selectedSlot ?: 0, dir) }
                RotationManager.queue(when (timing.value) {
                    Timing.VANILLA -> InstantRequest(rot, 150, action)
                    Timing.SEQUENTIAL -> RotationRequest(rot, 150, action)
                    Timing.GRIM -> GrimRequest(rot, 150, action)
                })
            }
            if (forced || !rotate.value) breakBlock(slot, p.inventory.selectedSlot, dir)
            return false
        }

        /** A GRIM rebreak may go now (once auto-crystal has locked the spot, when awaiting it). */
        fun rebreakReady(): Boolean {
            if (breaks <= 0 || rebreak.value != Rebreak.GRIM) return false
            if (aboveTarget(pos) && awaitAutoCrystal.value && !PlacementLock.isLocked(pos)) return false
            return pos == lastStarted || queue.size <= 1
        }

        /** Tool in, STOP, tool back (one tick later on a double-mine block's first break, or with 1.21). */
        private fun breakBlock(slot: Int, prev: Int, dir: Direction) {
            swap(slot, -1)
            toolSwapInFlight.set(true)
            seq(STOP, pos, dir)
            if (rebreak.value == Rebreak.NCP_AIR) {
                abortPacket(pos)
                seq(STOP, pos, dir)
                seq(START, pos, dir)
            }
            Modules.get<AutoCrystal>().minedMap[pos] = System.currentTimeMillis()
            samples.clear()
            // 26.3 has no swing packet to send here.
            if ((doubleMine.value && breaks < 1) || n121.value) Scheduler.ticks(1) { swap(prev, slot) } else swap(prev, slot)
            breakTimer.reset()
            lastBroken = pos
            breaks++
            recent[pos] = System.currentTimeMillis()
            prevProgress = 0f
            progress = 0f
        }

        fun abort() {
            samples.clear()
            if (progress != 0f) abortPacket(pos)
        }

        /** Start over (after a switch with NCP_AIR, a block that's air only gets a START). */
        fun reset(reason: ResetReason) {
            samples.clear()
            val p = mc.player ?: return
            val dir = Placer.visibleFaces(pos).firstOrNull() ?: p.direction
            if (reason == ResetReason.SWITCH && rebreak.value == Rebreak.NCP_AIR && mc.level?.isEmptyBlock(pos) == true) {
                progress = 0f
                prevProgress = 0f
                seq(START, pos, dir)
                return
            }
            if (progress != 0f) {
                progress = 0f
                prevProgress = 0f
                abortPacket(pos)
                start(dir)
            }
        }
    }

    /** The queue (at most two blocks). */
    val queue = LinkedList<Entry>()

    /** Blocks lined up past the queue, no packets sent yet (see [maxBlocks]). */
    private val pending = LinkedList<Pair<BlockPos, Direction>>()

    /** The last block started (the newest entry; the one kept for rebreak). */
    private var lastStarted: BlockPos? = null

    /** The last block broken, and when. */
    private var lastBroken: BlockPos? = null
    private val breakTimer = MsTimer()

    /** Blocks a STOP went out for, and when (await, phase). */
    private val recent = ConcurrentHashMap<BlockPos, Long>()

    /** Set while Speed Mine is switched to its tool; placer modules pause meanwhile. */
    val toolSwapInFlight = AtomicBoolean(false)

    init {
        on<PlayerUpdateEvent>(-10) { tick() }
        on<BlockBreakProgressEvent> { e ->
            if (mode.value != Mode.PACKET) return@on
            e.cancel()
            // Holding the button onto a block that isn't being mined starts it, as a click would.
            val p = mc.player ?: return@on
            if (hold.value && !p.abilities.instabuild && !inQueue(e.pos) && canMine(e.pos)) startMining(e.pos, e.direction)
        }
        on<AttackBlockEvent> { e ->
            val p = mc.player ?: return@on
            if (canMine(e.pos) && !p.abilities.instabuild && mode.value != Mode.DAMAGE) {
                e.cancel()
                startMining(e.pos, e.direction)
            }
        }
        on<AfterReceivePacketEvent> { e ->
            val pk = e.packet as? ClientboundBlockUpdatePacket ?: return@on
            if (rebreak.value != Rebreak.GRIM || !instantAsync.value || pk.blockState.block != Blocks.OBSIDIAN) return@on
            for (entry in queue) {
                if (entry.rebreakReady() && entry.pos == pk.pos && !(aboveTarget(pk.pos) && awaitAutoCrystal.value)) entry.update(true)
            }
        }
        on<AfterSendPacketEvent> { e ->
            if (e.packet is ServerboundSetCarriedItemPacket && resetOnSwitch.value && switchMode.value != SwitchMode.Silent &&
                rebreak.value != Rebreak.GRIM) {
                Scheduler.ticks(0) { for (entry in queue) entry.reset(ResetReason.SWITCH) }
            }
        }
        on<CollisionEvent> { e -> if (phaseActive() && recent.containsKey(e.pos)) e.state = Blocks.AIR.defaultBlockState() }
        on<Render3DEvent> { render(it.partialTick) }
    }

    override fun onEnable() = resetAll(ResetReason.TOGGLE)

    override fun onDisable() = resetAll(ResetReason.TOGGLE)

    private fun tick() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (p.abilities.instabuild || toolSwapInFlight.get()) return
        if (p.isUsingItem) {
            if (onEating.value == OnEating.PAUSE) return
            if (onEating.value == OnEating.RESET) resetAll(ResetReason.EATING)
        }
        val gm = mc.gameMode as? GameModeAccessor
        if (mode.value == Mode.DAMAGE && gm != null && gm.horizon_getDestroyProgress() < speed.value) gm.horizon_setDestroyProgress(speed.value)
        val now = System.currentTimeMillis()
        for ((pos, t) in recent) if (now - t > 200 || level.isEmptyBlock(pos)) recent.remove(pos)
        queue.removeIf { it.update(false) }
        startPending()
    }

    /** The next two lined-up blocks, once nothing in the queue is left to break. */
    private fun startPending() {
        if (mode.value != Mode.PACKET || !doubleMine.value || maxBlocks.value <= 2) pending.clear()
        val level = mc.level ?: return
        if (pending.isEmpty() || queue.any { !level.isEmptyBlock(it.pos) }) return
        queue.clear()
        while (queue.size < 2 && pending.isNotEmpty()) {
            val (pos, dir) = pending.removeFirst()
            if (!level.isEmptyBlock(pos) && inReach(pos)) begin(pos, dir)
        }
    }

    /** Reset every entry, then forget them. */
    private fun resetAll(reason: ResetReason) {
        for (e in queue) e.reset(reason)
        queue.clear()
        pending.clear()
        lastBroken = null
        toolSwapInFlight.set(false)
    }

    fun inQueue(pos: BlockPos) = queue.any { it.pos == pos } || pending.any { it.first == pos }

    /** No room for another block (double-mine: [maxBlocks] queued; else the one entry is still solid). */
    fun busy() = if (doubleMine.value) queue.size + pending.size >= maxBlocks.value else queue.isNotEmpty() && queue.first().prevProgress != -1f

    /** Breakable and in range (+0.5); with double-mine far blocks pass too, unless it's the newest one. */
    fun canMine(pos: BlockPos): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (level.getBlockState(pos).block.defaultDestroyTime() == -1f) return false
        if (inReach(pos)) return true
        return doubleMine.value && pos != lastStarted
    }

    private fun inReach(pos: BlockPos): Boolean {
        val p = mc.player ?: return false
        return p.eyePosition.distanceTo(Vec3.atCenterOf(pos)) <= p.blockInteractionRange() + 0.5f
    }

    /**
     * Start mining a block (single: replaces the current one; double-mine: replaces the newest of two). With [maxBlocks]
     * above two, a block past a queue still mining is lined up instead (replacing the newest lined up when full).
     */
    fun startMining(pos: BlockPos, dir: Direction) {
        val p = mc.player ?: return
        if (!canMine(pos) || p.abilities.instabuild) return
        if (mode.value == Mode.DAMAGE) return
        if (inQueue(pos)) return
        if (doubleMine.value && maxBlocks.value > 2 && queue.size >= 2) {
            if (queue.size + pending.size >= maxBlocks.value) pending.removeLast()
            pending.addLast(pos to dir)
            return
        }
        begin(pos, dir)
    }

    private fun begin(pos: BlockPos, dir: Direction) {
        val p = mc.player ?: return
        if (!doubleMine.value && queue.isNotEmpty()) queue.removeFirst().abort()
        if (doubleMine.value && queue.size >= 2) {
            val e = queue.first { it.pos == lastStarted }
            e.abort()
            queue.remove(e)
        }
        lastStarted = pos
        if (queue.size >= 2) throw Exception("Already mining!")
        queue.add(Entry(pos, dir))
        p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
    }

    /**
     * Blocks about to break (or the one just broken, with [includeRecent]); AutoCrystal
     * treats them as air.
     */
    fun aboutToBreak(includeRecent: Boolean): List<BlockPos> {
        if (queue.isEmpty()) return emptyList()
        if (queue.size == 1 && soon(queue.first())) return listOf(queue.first().pos)
        if (queue.size == 2) {
            val e = queue.first { it.pos == lastStarted }
            if (soon(e)) return listOf(queue.first().pos, e.pos)
        }
        val w = lastBroken
        if (!breakTimer.passed(200) && w != null && includeRecent) return listOf(w)
        return emptyList()
    }

    private fun soon(e: Entry): Boolean =
        e.progress < 1f && e.progress + (e.progress - e.prevProgress) * 2.5f >= 1f && mc.level?.isEmptyBlock(e.pos) == false

    /** Recently broken blocks collide as air for 200 ms. */
    fun phaseActive() = enabled && phase.value && recent.values.any { System.currentTimeMillis() - it < 200 }

    /** The block is above auto-crystal's target's head. */
    private fun aboveTarget(pos: BlockPos): Boolean {
        val ac = Modules.get<AutoCrystal>()
        val t = ac.target ?: return false
        return ac.enabled && pos.y.toDouble() > t.y + 1
    }

    private fun seq(action: ServerboundPlayerActionPacket.Action, pos: BlockPos, dir: Direction) =
        Packets.sendSequenced { ServerboundPlayerActionPacket(action, pos, dir, it) }

    private fun abortPacket(pos: BlockPos) =
        Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN))

    /** Progress per tick with the given tool slot (its own maths, not vanilla's). */
    private fun delta(state: BlockState, pos: BlockPos, slot: Int, onGround: Boolean): Float {
        val p = mc.player ?: return 0f
        val level = mc.level ?: return 0f
        if (state == Blocks.AIR.defaultBlockState()) return 0f
        val hard = state.getDestroySpeed(level, pos)
        if (hard < 0) return 0f
        val stack = if (slot == -1) p.mainHandItem else p.inventory.getItem(slot)
        val div = if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) 100f else 30f
        return speed(state, slot, onGround) / hard / div
    }

    private fun speed(state: BlockState, slot: Int, onGround: Boolean): Float {
        val p = mc.player ?: return 0f
        var s = 1f
        val stack = if (slot == -1) ItemStack.EMPTY else p.inventory.getItem(slot)
        if (slot != -1 && !stack.isEmpty) s *= stack.getDestroySpeed(state)
        if (s > 1f && slot != -1) {
            val eff = efficiency(stack)
            if (eff > 0 && !stack.isEmpty) s += (StrictMath.pow(eff.toDouble(), 2.0) + 1).toFloat()
        }
        p.getEffect(MobEffects.HASTE)?.let { s *= 1f + (it.amplifier + 1) * 0.2f }
        p.getEffect(MobEffects.MINING_FATIGUE)?.let { s *= Math.pow(0.3f.toDouble(), (it.amplifier + 1).toDouble()).toFloat() }
        if (p.isUnderWater) s *= p.getAttribute(Attributes.SUBMERGED_MINING_SPEED)!!.value.toFloat()
        if (!onGround && !Modules.get<FreeCam>().enabled) s /= 5f
        return if (s < 0) 0f else s * factor.value
    }

    private fun efficiency(stack: ItemStack): Int {
        val level = mc.level ?: return 0
        val e = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY)
        return EnchantmentHelper.getItemEnchantmentLevel(e, stack)
    }

    /** The best tool's inventory index (−1 for air): among correct tools, else among all. */
    private fun bestTool(pos: BlockPos): Int {
        val r = toolPass(pos, true)
        return if (r != -1) r else toolPass(pos, false)
    }

    /**
     * Main inventory first, then the hotbar; score = efficiency + destroy speed. The correct-tool pass keeps the
     * LOWEST score on purpose (so the weakest correct tool); the fallback keeps the highest.
     */
    private fun toolPass(pos: BlockPos, requireCorrect: Boolean): Int {
        val p = mc.player ?: return -1
        val level = mc.level ?: return -1
        val state = level.getBlockState(pos)
        if (state.block is AirBlock) return -1
        var best = -1
        var bestScore = if (requireCorrect) Float.MAX_VALUE else Float.MIN_VALUE
        val saver = Modules.get<ToolSaver>().enabled
        for (i in 9..44) {
            val stack = p.inventory.getItem(if (i >= 36) i - 36 else i)
            if (stack === ItemStack.EMPTY) continue
            if (requireCorrect && !stack.isCorrectToolForDrops(state)) continue
            if (stack.maxDamage - stack.damageValue <= 4 && saver) continue
            val score = efficiency(stack) + stack.getDestroySpeed(state)
            if (if (requireCorrect) score < bestScore else score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return if (best >= 36) best - 36 else best
    }

    /** Bring tool [a] in (b = −1), or put [b]'s stack back with [a] as the slot to return to. */
    private fun swap(a: Int, b: Int) {
        val p = mc.player ?: return
        if (switchMode.value != SwitchMode.Alternative && a < 9) {
            if (switchMode.value == SwitchMode.Silent) {
                if (Inventory.serverSlot != a) Packets.send(ServerboundSetCarriedItemPacket(a))
            } else {
                Inventory.selectHotbar(a)
            }
        } else {
            val s = if (b == -1) a else b
            Inventory.swap(if (s < 9) s + 36 else s, p.inventory.selectedSlot, true)
        }
        Modules.get<AutoCrystal>().altSwapActive.set(false)
        toolSwapInFlight.set(false)
    }

    /** The hold outline and the progress box of each block. */
    private fun render(pt: Float) {
        if (mode.value == Mode.DAMAGE) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        for ((pos, _) in pending) World3D.outline(AABB(pos), Colors.withAlpha(start.value.rgb, 1f))
        for (e in queue) {
            val showHold = selection.value == Selection.ALWAYS ||
                (selection.value == Selection.REBREAK && rebreak.value != Rebreak.OFF) ||
                (selection.value == Selection.AIR_REBREAK && rebreak.value != Rebreak.OFF && level.isEmptyBlock(e.pos))
            if (showHold) World3D.outline(AABB(e.pos), Colors.withAlpha(start.value.rgb, 1f))
            if (level.isEmptyBlock(e.pos)) continue
            var prog = Mth.clamp(e.prevProgress + (e.progress - e.prevProgress) * pt, 0f, 1f)
            if (onEating.value == OnEating.PAUSE && p.isUsingItem) prog = e.progress
            if (e.progress >= 1f) prog = 1f
            val x = e.pos.x.toDouble()
            val y = e.pos.y.toDouble()
            val z = e.pos.z.toDouble()
            val d = prog.toDouble()
            val box = when (renderMode.value) {
                RenderMode.Block -> AABB(e.pos)
                RenderMode.Grow -> AABB(x, y, z, x + 1, y + d, z + 1)
                RenderMode.Shrink -> AABB(x, y, z, x, y, z).contract(d, d, d).move(0.5 + d * 0.5, 0.5 + d * 0.5, 0.5 + d * 0.5)
            }
            World3D.fill(box, Colors.lerp(start.value.rgb, end.value.rgb, prog))
            World3D.outline(box, Colors.lerp(Colors.withAlpha(start.value.rgb, 1f), Colors.withAlpha(end.value.rgb, 1f), prog))
        }
    }

    private companion object {
        val START = ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
        val STOP = ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
    }
}
