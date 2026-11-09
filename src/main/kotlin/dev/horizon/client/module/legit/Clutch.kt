package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Saves a fall into the void the way a player clutches. Knocked off (or jumping off) with nothing
 * to land on within [minDrop] blocks, it turns the camera onto the side of a block nearby and right-clicks through
 * vanilla, so the new block is under you before your feet sink past it. Knocked further out than one block from the
 * edge, it builds out to you: a short line of blocks from the edge to under your path, each against the last, picking
 * one it can finish in time (the fall is played out with vanilla's physics, keys held included).
 *
 * The camera moves like a hand on a mouse (Aura's HUMAN model): after [reaction] a quick bell-shaped flick that lands a
 * little off and corrects, never faster than [turnSpeed], in whole mouse counts. A click goes only when the crosshair is
 * on the face and the look the server already has (sent the tick before, since the click reaches it first) is on that
 * block too, so a server checking the click against your look takes it. While it saves a fall, Aura sends no hidden look and turns
 * nothing. Blocks come from the hotbar if you aren't holding any. Walking straight off an edge leaves no time for this
 * (for a player either); it's the time a knock-up or a jump gives above the edge that makes the save. A jump that falls
 * short of an island counts too: the fall is played out against walls, so one that hits the island's side and slides
 * down it is seen as a fall (and the island's side is a block to build against).
 */
class Clutch : Module("clutch", Categories.LEGIT) {
    enum class FilterMode { NONE, BLACKLIST, WHITELIST }

    /** Fastest the camera turns, in degrees a second; a flick in a clutch is quick. */
    val turnSpeed = float("turn-speed", 900.0f, 200.0f..2000.0f)
    /** Milliseconds between seeing the fall and starting to turn. */
    val reaction = int("reaction", 40, 0..200)
    /** A fall to ground this many blocks down (or none at all) is one to save. */
    val minDrop = int("min-drop", 4, 2..30)
    val switchBlocks = bool("switch-blocks", true)
    val blockSelection = enum("block-selection", FilterMode.BLACKLIST)
    val blockList = blocks("blocks", blockList("block.minecraft.tnt", "block.minecraft.sand", "block.minecraft.gravel")) { blockSelection.value != FilterMode.NONE }

    private val brain = ClutchBrain(Rng.random)
    private val probe = BlockPos.MutableBlockPos()
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    /** Mid-clutch with somewhere to aim: [FireballDeflect], [PearlSave] and [BedBreaker] leave the camera to it. */
    val busy get() = enabled && brain.clutching && brain.plan != null

    /** Saving a fall with blocks to do it: the look sent to the server must be the camera's ([dev.horizon.client.module.attack.Aura] holds off). */
    val clutching get() = enabled && brain.clutching

    override fun onEnable() = reset()
    override fun onDisable() = reset()

    private fun reset() {
        brain.reset()
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    private val world = object : ClutchPlanner.World {
        override fun solid(x: Int, y: Int, z: Int): Boolean {
            val level = mc.level ?: return false
            probe.set(x, y, z)
            return !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty
        }

        override fun free(x: Int, y: Int, z: Int): Boolean {
            val level = mc.level ?: return false
            probe.set(x, y, z)
            return level.getBlockState(probe).canBeReplaced()
        }

        override fun support(x: Int, y: Int, z: Int): Boolean {
            val level = mc.level ?: return false
            probe.set(x, y, z)
            val state = level.getBlockState(probe)
            if (state.canBeReplaced() || state.getMenuProvider(level, probe) != null) return false
            // A right click on these uses them instead of placing against them.
            val b = state.block
            return b !is BedBlock && b !is DoorBlock && b !is TrapDoorBlock && b !is FenceGateBlock
        }

        override fun ray(ex: Double, ey: Double, ez: Double, dx: Double, dy: Double, dz: Double, reach: Double): BridgePlanner.Hit? {
            val level = mc.level ?: return null
            val from = Vec3(ex, ey, ez)
            val hit = level.clip(ClipContext(from, from.add(dx * reach, dy * reach, dz * reach), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, mc.player ?: return null))
            if (hit.type != HitResult.Type.BLOCK) return null
            return BridgePlanner.Hit(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z, hit.direction.ordinal, hit.location.distanceTo(from))
        }
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (mc.gui.screen() != null || p.abilities.flying || p.isInLiquid || p.isPassenger || p.isFallFlying ||
                p.isSpectator || godBridging(p)) {
                brain.reset()
                return@on
            }
            val blocks = isValidBlock(p.mainHandItem) || (switchBlocks.value && Inventory.hotbarFind { isValidBlock(it) } != null)
            // The crosshair as vanilla's own right click sees it this tick, and what the look the server has (the last
            // one sent) points at from the same eyes: the click is judged by that one.
            (mc as MinecraftAccessor).horizon_pick(1f)
            val eye = p.eyePosition
            val pick = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.let {
                BridgePlanner.Hit(it.blockPos.x, it.blockPos.y, it.blockPos.z, it.direction.ordinal, it.location.distanceTo(eye))
            }
            val reach = p.blockInteractionRange()
            val sl = ClutchPlanner.look(Rotation.lastYaw.toDouble(), Rotation.lastPitch.toDouble())
            val sent = world.ray(eye.x, eye.y, eye.z, sl[0], sl[1], sl[2], reach)
            val v = p.deltaMovement
            val player = ClutchBrain.Player(p.x, p.y, p.z, v.x, v.y, v.z, p.onGround(), p.xxa.toDouble(), p.zza.toDouble(),
                p.isSprinting, p.yRot.toDouble(), p.xRot.toDouble(), p.bbWidth / 2.0, p.bbHeight.toDouble(),
                p.eyeHeight.toDouble(), p.gravity, p.getAttributeValue(Attributes.JUMP_STRENGTH), reach, p.tickCount)
            val cell = brain.tick(System.nanoTime() / 1e9, player, world, settings(), blocks, pick, sent, p.isUsingItem, lateBy())
            // Nothing to build and nothing to click: leave the hotbar alone (Pearl Save may be getting a pearl out).
            if (brain.plan == null && cell == null) return@on
            if (!isValidBlock(p.mainHandItem)) {
                if (!switchBlocks.value) return@on
                val found = Inventory.hotbarFind { isValidBlock(it) } ?: return@on
                Inventory.selectHotbar(found.slot)
            }
            if (cell != null && isValidBlock(p.mainHandItem)) {
                (mc as MinecraftAccessor).horizon_startUseItem()
                val pos = BlockPos(ClutchPlanner.unkeyX(cell), ClutchPlanner.unkeyY(cell), ClutchPlanner.unkeyZ(cell))
                // Placed: the next tick plans again from it (the line's next block, or nothing left to do).
                if (!level.getBlockState(pos).canBeReplaced()) brain.placed(p.tickCount, cell)
            }
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now
            if (mc.gui.screen() != null || brain.since.isNaN()) return@on
            // Where the next tick starts (the player's position, not the one drawn), and the eyes as drawn this frame
            // and how fast they move.
            val eye = p.getEyePosition(mc.deltaTracker.getGameTimeDeltaPartialTick(true))
            val vel = p.position().subtract(p.xo, p.yo, p.zo).scale(20.0)
            val d = brain.frame(now / 1e9, dt, p.yRot.toDouble(), p.xRot.toDouble(), p.x, p.y, p.z, eye.x, eye.y, eye.z,
                vel.x, vel.y, vel.z, p.eyeHeight.toDouble(), p.blockInteractionRange(), world)
            // In whole mouse counts: vanilla turns 0.15 x 8 f^3 degrees a count, f being 0.6 x sensitivity + 0.2.
            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15
            carryX += d[0] / step
            carryY += d[1] / step
            val countsX = carryX.toInt()
            val countsY = carryY.toInt()
            carryX -= countsX
            carryY -= countsY
            if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        }
    }

    private fun settings() = ClutchBrain.Settings(reaction.value / 1000.0, minDrop.value, turnSpeed.value.toDouble())

    /**
     * With a pearl ready for Pearl Save, only a line done in time is tried (else the camera is Pearl Save's); without
     * one, a line a few ticks late is still worth a try.
     */
    private fun lateBy(): Int {
        val ps = Modules.get<PearlSave>()
        if (!ps.enabled) return LATE
        val pearl = Inventory.hotbarFind(Items.ENDER_PEARL) ?: return LATE
        return if (mc.player?.cooldowns?.isOnCooldown(pearl.stack) == true) LATE else 0
    }

    /** GodBridge jumps over the void on purpose; its own planner looks after those. */
    private fun godBridging(p: LocalPlayer): Boolean {
        val gb = Modules.get<GodBridge>()
        if (!gb.enabled || gb.mode.value != GodBridge.Mode.GOD_BRIDGE) return false
        return Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)
    }

    private fun isValidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.item !is BlockItem) return false
        val block = (stack.item as BlockItem).block
        if (!block.defaultBlockState().isCollisionShapeFullBlock(mc.level ?: return false, BlockPos.ZERO)) return false
        return when (blockSelection.value) {
            FilterMode.NONE -> true
            FilterMode.BLACKLIST -> block !in blockList.value
            FilterMode.WHITELIST -> block in blockList.value
        }
    }

    private companion object {
        /** How late (ticks) a line may be and still be tried, with no pearl to fall back on. */
        const val LATE = 8
    }
}
