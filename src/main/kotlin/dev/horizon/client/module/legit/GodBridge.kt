package dev.horizon.client.module.legit

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.Rng
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.basic.Sprint
import dev.horizon.client.render.Fonts
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GodBridge assist module.
 * Assists with advanced bridging techniques:
 * - Legit: Automatically sneaks at the edge of blocks when moving backwards (Ninja / Fast Bridge).
 * - GodBridge: Automates a hand-played god bridge. The camera is held still (or turned with ordinary mouse counts
 *   onto the set angle), blocks are placed by vanilla's own right-click on whatever the crosshair is on (no custom
 *   rotation packets, no fabricated hit positions), and the jumps are real input presses timed by [BridgePlanner]:
 *   at a fixed angle every few blocks no tick lands where a click can build, and a jump is what carries it over.
 * Includes Pitch Check, Auto-Angle / Diagonal Snap, Activation Blocks requirement, Block Count HUD display,
 * and Block Selection (Whitelist/Blacklist).
 */
class GodBridge : Module("godbridge", Categories.LEGIT, listOf("god-bridge", "scaffold-legit", "fast-bridge-plus")) {
    enum class Mode { LEGIT, GOD_BRIDGE }
    enum class FilterMode { NONE, BLACKLIST, WHITELIST }
    enum class AngleMode { NONE, SMOOTH_DIAGONAL, SNAP_DIAGONAL, HARD_LOCK }

    val mode = enum("mode", Mode.GOD_BRIDGE)

    // Legit mode settings
    val sneakDelay = int("sneak-delay", 2, 0..10) { mode.value == Mode.LEGIT }
    val requireSneak = bool("require-sneak", false) { mode.value == Mode.LEGIT }
    val edgeDistance = float("edge-distance", 0.18f, 0.05f..0.29f) { mode.value == Mode.LEGIT }

    // GodBridge mode settings
    val autoAngle = enum("auto-angle", AngleMode.NONE) { mode.value == Mode.GOD_BRIDGE }
    val aimSpeed = float("aim-speed", 300.0f, 60.0f..600.0f) { mode.value == Mode.GOD_BRIDGE && autoAngle.value == AngleMode.SMOOTH_DIAGONAL }
    /**
     * For a diagonal stance (73-77 holds); facing straight back, the pitch that reaches as far along the bridge is
     * used instead (74 -> 78.5, where 77.5-80 holds).
     */
    val targetPitch = float("target-pitch", 74.0f, 65.0f..85.0f) { mode.value == Mode.GOD_BRIDGE && autoAngle.value != AngleMode.NONE }
    val autoJump = bool("auto-jump", true) { mode.value == Mode.GOD_BRIDGE }
    val jumpInterval = int("jump-interval", 7, 3..15) { mode.value == Mode.GOD_BRIDGE && autoJump.value }
    val activationBlocks = int("activation-blocks", 3, 0..10) { mode.value == Mode.GOD_BRIDGE }
    val placeDelay = int("place-delay", 0, 0..5) { mode.value == Mode.GOD_BRIDGE }
    val requireRightClick = bool("require-right-click", false) { mode.value == Mode.GOD_BRIDGE }
    /** Each jump also steps the bridge up a block (a block under the feet at the top of the jump); off, it stays flat. */
    val climb = bool("climb", false) { mode.value == Mode.GOD_BRIDGE && autoJump.value }

    // General settings
    val blockCount = bool("block-count", true)
    val pitchCheck = bool("pitch-check", true)
    val minPitch = float("min-pitch", 55.0f, 25.0f..85.0f) { pitchCheck.value }
    val blockSelection = enum("block-selection", FilterMode.NONE)
    val blockList = blocks("blocks", blockList("block.minecraft.tnt", "block.minecraft.sand", "block.minecraft.gravel")) { blockSelection.value != FilterMode.NONE }

    private var sneaking = false
    private var sneakTimer = 0
    private var placedCount = 0
    private var activated = false
    private var bridgeBlocksPlaced = 0
    private var lastPlaceTick = 0
    private var lastActiveTick = 0
    private var lastPlacedPos: BlockPos? = null
    /** A use was sent for this position; wait for its local block update before counting it. */
    private val pending = ArrayList<Pair<BlockPos, Int>>()
    /** Ticks to wait before the next use, re-rolled after each one like a human's click rhythm. */
    private var nextDelay = 0
    /** Set by this tick's jump plan; applied to this tick's input as a real jump key press. */
    private var jumpRequested = false
    private val probe = BlockPos.MutableBlockPos()
    /** The diagonal heading's current lean, see [sideNudge]. */
    private var nudge = 0.0

    // Legit camera rotation physics variables
    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L

    override fun onEnable() = resetState()
    override fun onDisable() = resetState()

    private fun resetState() {
        sneaking = false
        sneakTimer = 0
        placedCount = 0
        activated = (activationBlocks.value == 0)
        bridgeBlocksPlaced = 0
        lastPlaceTick = 0
        lastActiveTick = 0
        lastPlacedPos = null
        pending.clear()
        nextDelay = 0
        jumpRequested = false
        nudge = 0.0
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
    }

    init {
        // Track manual block placements for activation
        on<SendPacketEvent> { e ->
            if (mode.value != Mode.GOD_BRIDGE) return@on
            val pk = e.packet as? ServerboundUseItemOnPacket ?: return@on
            val p = mc.player ?: return@on
            if (activated) return@on

            val stack = p.getItemInHand(pk.hand)
            if (!isValidBlock(stack)) return@on

            val movingBack = Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)
            if (movingBack) {
                val clickPos = pk.hitResult.blockPos
                val face = pk.hitResult.direction
                val placedPos = clickPos.relative(face)
                if (placedPos.y <= p.blockY && placedPos != lastPlacedPos) {
                    lastPlacedPos = placedPos
                    placedCount++
                    lastActiveTick = p.tickCount
                    if (placedCount >= activationBlocks.value) {
                        activated = true
                        bridgeBlocksPlaced = 0
                    }
                }
            }
        }

        // Handle Legit mode sneaking via input tick
        on<KeyboardInputEvent>(-10) {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val keys = p.input.keyPresses

            if (mode.value != Mode.LEGIT) {
                sneaking = false
                // The block-interval jump goes through the input like a real space press.
                if (jumpRequested && p.onGround()) {
                    if (!keys.jump()) {
                        p.input.keyPresses = Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint())
                    }
                    jumpRequested = false
                }
                return@on
            }

            if (!activeForLegit(p, keys)) {
                sneaking = false
                sneakTimer = 0
                return@on
            }

            val edge = overEdge(p, level, keys)
            if (edge && (p.onGround() || sneaking)) {
                sneaking = true
                sneakTimer = sneakDelay.value
            } else if (sneaking) {
                if (sneakTimer > 0) {
                    sneakTimer--
                } else {
                    sneaking = false
                }
            }

            if (sneaking && !keys.shift()) {
                Modules.get<Sprint>().releaseKey()
                p.input.keyPresses = Input(
                    keys.forward(),
                    keys.backward(),
                    keys.left(),
                    keys.right(),
                    keys.jump(),
                    true,
                    false
                )
            }
        }

        // GodBridge mode: aims like a player (camera turns only) and uses the item through vanilla's own
        // right-click path on whatever the crosshair is really on, so every packet matches a hand-played bridge.
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (mc.gui.screen() != null) return@on
            if (mode.value != Mode.GOD_BRIDGE) return@on
            jumpRequested = false

            if (p.abilities.flying || p.isInLiquid || p.isPassenger || p.isFallFlying) {
                resetState()
                return@on
            }

            // Must be moving backwards or diagonally backwards to bridge
            val movingBack = Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)

            if (!movingBack) {
                // If stopped moving for more than 25 ticks, reset activation so manual bridging is required again
                if (p.tickCount - lastActiveTick > 25) {
                    if (activationBlocks.value > 0) activated = false
                    placedCount = 0
                    bridgeBlocksPlaced = 0
                }
                vYaw = 0.0
                vPitch = 0.0
                return@on
            }
            lastActiveTick = p.tickCount

            // With manual aiming, preserve the pitch guard. Auto-angle puts the crosshair on the bridge itself,
            // so gating it before it can turn would deadlock.
            if (pitchCheck.value && autoAngle.value == AngleMode.NONE && p.xRot < minPitch.value) return@on

            if (requireRightClick.value && !(PlayerState.physical(mc.options.keyUse) || mc.options.keyUse.isDown)) return@on

            // Waiting for manual activation blocks to complete before taking over
            if (!activated) return@on

            if (!isValidBlock(p.mainHandItem)) {
                val found = Inventory.hotbarFind { isValidBlock(it) } ?: return@on
                Inventory.selectHotbar(found.slot)
                return@on
            }

            countPending(p, level)

            // Vanilla's own right click picks the crosshair at the tick's position just before handling the keys;
            // the one left from the last frame lags part of a tick behind, which is a lot at the bridge's edge.
            (mc as MinecraftAccessor).horizon_pick(1f)
            place(p, level)
            jumpRequested = autoJump.value && p.onGround() && planJump(p, level)
        }

        // Camera steering on MouseTurnEvent: ordinary mouse-count turns, so sensitivity steps are respected.
        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            val angle = autoAngle.value
            if (mode.value != Mode.GOD_BRIDGE || angle == AngleMode.NONE) return@on
            // A fireball deflect or a pearl throw has the camera meanwhile.
            if (Modules.get<FireballDeflect>().busy || Modules.get<PearlSave>().busy) return@on

            // Only steer when activated and actively bridging backwards
            if (!activated) return@on
            val movingBack = Movement.moving() && (p.input.keyPresses.backward() || p.input.moveVector.y < 0f)
            if (!movingBack) {
                vYaw = 0.0
                vPitch = 0.0
                return@on
            }
            if (requireRightClick.value && !(PlayerState.physical(mc.options.keyUse) || mc.options.keyUse.isDown)) {
                vYaw = 0.0
                vPitch = 0.0
                return@on
            }

            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now

            // Nearest diagonal / axis heading and the configured pitch. The camera never chases the bridge's edge:
            // the pitch only settles on the set angle, and placing is left to wherever the crosshair really is.
            val heading = (p.yRot / 45f).roundToInt() * 45
            val diagonal = heading % 90 != 0
            val wantYaw = heading + if (diagonal) sideNudge(p, heading.toDouble()) else 0.0
            val wantPitch = if (diagonal) targetPitch.value.toDouble() else straightPitch(targetPitch.value.toDouble())
            var errYaw = Mth.wrapDegrees((wantYaw - p.yRot).toFloat()).toDouble()
            var errPitch = wantPitch - p.xRot

            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15

            // Within a mouse count of the angle is as close as a mouse gets: hold still there rather than stepping
            // back and forth across it like no hand does (and a flicker of pitch moves where the crosshair reaches).
            if (abs(errYaw) < step) {
                errYaw = 0.0
                vYaw = 0.0
                carryX = 0.0
            }
            if (abs(errPitch) < step) {
                errPitch = 0.0
                vPitch = 0.0
                carryY = 0.0
            }

            if (angle == AngleMode.SMOOTH_DIAGONAL) {
                val maxSpeed = aimSpeed.value.toDouble()
                val stiffness = 160.0
                val damping = 0.85
                val c = 2.0 * sqrt(stiffness) * damping
                vYaw = (vYaw + (stiffness * errYaw - c * vYaw) * dt).coerceIn(-maxSpeed, maxSpeed)
                vPitch = (vPitch + (stiffness * errPitch - c * vPitch) * dt).coerceIn(-maxSpeed, maxSpeed)
                carryX += vYaw * dt / step
                carryY += vPitch * dt / step
            } else {
                // SNAP / HARD_LOCK: close the whole error at once, still in whole mouse counts.
                carryX += errYaw / step
                carryY += errPitch / step
            }
            val countsX = carryX.toInt()
            val countsY = carryY.toInt()
            carryX -= countsX
            carryY -= countsY
            if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        }

        // Render Block Count in center of screen
        on<Render2DEvent> { e ->
            if (!blockCount.value) return@on
            val p = mc.player ?: return@on
            val totalBlocks = countUsableBlocks(p)
            val status = if (mode.value == Mode.GOD_BRIDGE && activationBlocks.value > 0 && !activated) {
                " [$placedCount/${activationBlocks.value}]"
            } else ""
            val text = "$totalBlocks blocks$status"
            val font = Fonts.medium8
            val w = font.width(text)
            val cx = mc.window.guiScaledWidth / 2f
            val cy = mc.window.guiScaledHeight / 2f + 16f
            val col = if (activated) {
                if (totalBlocks > 32) 0xFF55FF55.toInt() else if (totalBlocks > 10) 0xFFFFAA00.toInt() else 0xFFFF5555.toInt()
            } else {
                0xFFF5F5F7.toInt()
            }
            font.draw(text, cx - w / 2f, cy, col)
            font.flush()
        }
    }

    /** Right-clicks through vanilla when the crosshair is on a face that extends the bridge. */
    private fun place(p: Player, level: Level) {
        if (p.tickCount - lastPlaceTick < nextDelay) return
        if (p.isUsingItem) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        if (!canBridgeOn(p, level, hit)) return

        // The real thing a right click would do: vanilla startUseItem on the current crosshair target.
        val target = hit.blockPos.relative(hit.direction)
        pending += target to p.tickCount
        lastPlaceTick = p.tickCount
        nextDelay = placeDelay.value + Rng.int(0, 1)
        (mc as MinecraftAccessor).horizon_startUseItem()
        // Counted for the jump interval; a refused placement takes the count back in countPending.
        if (target != lastPlacedPos) {
            lastPlacedPos = target
            bridgeBlocksPlaced++
        }
    }

    /**
     * Whether to jump this tick: when walking on would leave the bridge behind and a jump keeps it going, or, every
     * [jumpInterval] blocks, when a jump is safe anyway (a player's own rhythm).
     */
    private fun planJump(p: Player, level: Level): Boolean {
        val v = p.deltaMovement
        val s = BridgePlanner.State(p.x, p.y, p.z, v.x, v.y, v.z, true)
        val look = p.lookAngle
        val strafe = p.xxa.toDouble()
        val forward = p.zza.toDouble()
        val yaw = p.yRot.toDouble()
        val ground = BridgePlanner.push(strafe, forward, yaw, p.speed.toDouble())
        val air = BridgePlanner.push(strafe, forward, yaw, if (p.isSprinting) 0.026 else 0.02)
        val params = BridgePlanner.Params(
            look.x, look.y, look.z, ground[0], ground[1], air[0], air[1],
            eye = p.eyeHeight.toDouble(), halfWidth = p.bbWidth / 2.0, height = p.bbHeight.toDouble(),
            reach = p.blockInteractionRange(),
            jumpPower = p.getAttributeValue(Attributes.JUMP_STRENGTH) + p.jumpBoostPower,
            gravity = p.gravity,
            // The click delay's random extra tick is assumed every time, so the plan never counts on a quick one.
            clickGap = max(1, placeDelay.value + 1),
            climb = climb.value,
            nextClickIn = max(0, nextDelay - (p.tickCount - lastPlaceTick)),
        )
        val solid = { x: Int, y: Int, z: Int ->
            probe.set(x, y, z)
            !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty
        }
        if (BridgePlanner.shouldJump(s, params, solid)) {
            bridgeBlocksPlaced = 0
            return true
        }
        if (bridgeBlocksPlaced >= jumpInterval.value && BridgePlanner.jumpIsSafe(s, params, solid)) {
            bridgeBlocksPlaced = 0
            return true
        }
        return false
    }

    /**
     * Whether a right click on [hit] would extend the bridge: a solid, non-interactive block whose touching tile is
     * free, on its side (so the bridge stays flat; the top only for a [climb] step), below the walking level and close to
     * the feet, so the module never builds walls or towers.
     */
    private fun canBridgeOn(p: Player, level: Level, hit: BlockHitResult): Boolean {
        val clicked = level.getBlockState(hit.blockPos)
        if (clicked.canBeReplaced()) return false
        val target = hit.blockPos.relative(hit.direction)
        if (!level.getBlockState(target).canBeReplaced()) return false
        if (hit.direction == Direction.DOWN) return false
        if (hit.direction == Direction.UP && !steps(p, target)) return false
        if (target.y.toDouble() >= p.y) return false
        if (target.y < p.blockY - 2) return false
        // Only click plain blocks, never a chest, door, crafting table etc. next to the bridge.
        if (clicked.getMenuProvider(level, hit.blockPos) != null) return false
        return true
    }

    /**
     * With [climb], the top of a block may be clicked mid-jump for the step up: only for a block that will be under the
     * feet (the player is over it, and the feet are already clearly above it, or the server refuses it).
     */
    private fun steps(p: Player, target: BlockPos): Boolean {
        if (!climb.value || p.onGround() || p.y < target.y + 1.0 + BridgePlanner.STEP_CLEARANCE) return false
        val b = p.boundingBox
        return target.x < b.maxX && target.x + 1 > b.minX && target.z < b.maxZ && target.z + 1 > b.minZ
    }

    /** The pitch that, looking straight back along the bridge, reaches as far along it as [diagonal] does at 45 degrees. */
    private fun straightPitch(diagonal: Double) = Math.toDegrees(atan(tan(Math.toRadians(diagonal)) * sqrt(2.0)))

    /**
     * Degrees added to a diagonal heading so the walk carries the player onto the half of the block away from where
     * the crosshair runs. Looking 45 degrees off the bridge, the crosshair also moves sideways as it goes down: from the
     * middle of the block it runs off the side during a jump, from the far half it stays on the bridge. A player sets
     * this up by standing on the corner; here the walk leans over by a degree and a half until the player is there.
     * Kept as it was while in the air, so the camera doesn't swing back and forth over a jump.
     */
    private fun sideNudge(p: Player, heading: Double): Double {
        if (!p.onGround()) return nudge
        nudge = 0.0
        val move = BridgePlanner.push(p.xxa.toDouble(), p.zza.toDouble(), heading, 1.0)
        val len = hypot(move[0], move[1])
        if (len < 1e-3) return nudge
        // Across the walk, and which way along it the crosshair drifts.
        val sx = -move[1] / len
        val sz = move[0] / len
        val r = Math.toRadians(heading)
        val drift = -sin(r) * sx + cos(r) * sz
        if (abs(drift) < 0.1) return nudge
        val column = p.onPos
        val off = (p.x - (column.x + 0.5)) * sx + (p.z - (column.z + 0.5)) * sz
        val want = if (drift > 0) -SIDE else SIDE
        if (abs(off - want) < 0.1) return nudge
        val turned = BridgePlanner.push(p.xxa.toDouble(), p.zza.toDouble(), heading + 1.0, 1.0)
        val sideways = turned[0] * sx + turned[1] * sz
        nudge = if ((want > off) == (sideways > 0)) NUDGE else -NUDGE
        return nudge
    }

    /** Counts a sent use once its block shows up locally, and takes back the count of one that was refused. */
    private fun countPending(p: Player, level: Level) {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val (target, tick) = iter.next()
            val age = p.tickCount - tick
            if (level.getBlockState(target).canBeReplaced()) {
                // The client predicts the block the moment it uses; if it's gone again the server refused it
                // (or it never went down), so it is retried on the very next tick.
                if (age >= 1) {
                    iter.remove()
                    lastPlaceTick = 0
                    nextDelay = 0
                    if (bridgeBlocksPlaced > 0) bridgeBlocksPlaced--
                    lastPlacedPos = null
                }
            } else if (age >= 3) {
                // Still there after a server round trip: it really was placed.
                iter.remove()
            }
        }
    }

    private fun activeForLegit(p: Player, keys: Input): Boolean {
        if (p.abilities.flying || p.isInLiquid || p.isPassenger || p.isFallFlying) return false
        if (mc.gui.screen() != null && !Modules.get<GuiMove>().enabled) return false
        if (pitchCheck.value && p.xRot < minPitch.value) return false
        if (requireSneak.value && !(PlayerState.physical(mc.options.keyShift) || mc.options.keyShift.isDown)) return false
        if (!isValidBlock(p.mainHandItem) && !isValidBlock(p.offhandItem)) return false
        return keys.backward() || sneaking
    }

    private fun isValidBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.item !is BlockItem) return false
        val block = (stack.item as BlockItem).block
        return when (blockSelection.value) {
            FilterMode.NONE -> true
            FilterMode.BLACKLIST -> block !in blockList.value
            FilterMode.WHITELIST -> block in blockList.value
        }
    }

    private fun countUsableBlocks(p: Player): Int {
        var count = 0
        val inv = p.inventory
        for (i in 0 until minOf(36, inv.containerSize)) {
            val s = inv.getItem(i)
            if (isValidBlock(s)) count += s.count
        }
        return count
    }

    private fun overEdge(p: Player, level: Level, keys: Input): Boolean {
        val d = edgeDistance.value.toDouble().coerceIn(0.05, 0.29)
        val b = p.boundingBox
        val yMin = p.y - 0.5
        val yMax = p.y + 0.02
        val here = AABB(b.minX + d, yMin, b.minZ + d, b.maxX - d, yMax, b.maxZ - d)
        if (level.noCollision(p, here)) return true

        val impulse = inputVector(p.yRot, keys, 0.08)
        val dx = p.deltaMovement.x + impulse[0]
        val dz = p.deltaMovement.z + impulse[1]
        if (dx * dx + dz * dz < 1e-6) return false
        val ahead = AABB(b.minX + d + dx, yMin, b.minZ + d + dz, b.maxX - d + dx, yMax, b.maxZ - d + dz)
        return level.noCollision(p, ahead)
    }

    private fun inputVector(yawDeg: Float, keys: Input, scale: Double): DoubleArray {
        val fwd = (if (keys.forward()) 1.0 else 0.0) - (if (keys.backward()) 1.0 else 0.0)
        val str = (if (keys.left()) 1.0 else 0.0) - (if (keys.right()) 1.0 else 0.0)
        if (fwd == 0.0 && str == 0.0) return doubleArrayOf(0.0, 0.0)
        val len = Mth.invSqrt(fwd * fwd + str * str)
        val nf = fwd * len * scale
        val ns = str * len * scale
        val rad = Math.toRadians(yawDeg.toDouble())
        val s = sin(rad)
        val c = cos(rad)
        return doubleArrayOf(ns * c - nf * s, nf * c + ns * s)
    }

    private companion object {
        /** How far off the block's middle a diagonal bridger stands, see [sideNudge]. */
        const val SIDE = 0.3
        const val NUDGE = 1.5
    }
}
