package dev.horizon.client.module.legit

import dev.horizon.client.combat.BedTeams
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.event.MouseTurnEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.module.Animation
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Render
import dev.horizon.client.render.Theme
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Not in the original: Auto Bed Defender for BedWars.
 * Automatically scans for your team's bed (the nearest one that [BedTeams] doesn't know to be another team's), determines the core defense positions (surrounding and above the bed blocks),
 * picks the best defense blocks (prioritizing Obsidian for the inner core, and auto-swapping Obsidian from inventory
 * to hotbar if available, followed by End Stone, Wood, and Wool), and places them using legit smooth rotations.
 */
class BedDefender : Module("bed-defender", Categories.LEGIT, listOf("auto-bed-defense", "bed-defense", "bed-protect")) {
    val speed = float("speed", 250.0f, 60.0f..600.0f)
    val placeRange = float("place-range", 4.5f, 2.0f..6.0f)
    val searchRadius = int("search-radius", 8, 3..16)
    val placeDelay = int("place-delay", 1, 0..5)
    val autoRefillObby = bool("auto-refill-obby", true)
    val multiLayer = bool("multi-layer", false)
    val autoDisable = bool("auto-disable", false)

    private var targetHit: BlockHitResult? = null
    private var targetPos: BlockPos? = null
    private var vYaw = 0.0
    private var vPitch = 0.0
    private var carryX = 0.0
    private var carryY = 0.0
    private var lastNs = 0L
    private var delayTicks = 0
    private var foundBed: List<BlockPos> = emptyList()

    override fun onEnable() = resetState()
    override fun onDisable() = resetState()

    private fun resetState() {
        targetHit = null
        targetPos = null
        vYaw = 0.0
        vPitch = 0.0
        carryX = 0.0
        carryY = 0.0
        lastNs = 0L
        delayTicks = 0
        foundBed = emptyList()
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (mc.gui.screen() != null) return@on

            if (delayTicks > 0) delayTicks--

            // 1. Check for Obsidian in inventory to swap into hotbar if hotbar lacks it
            if (autoRefillObby.value) {
                val hotbarObby = Inventory.hotbarFind(Items.OBSIDIAN)
                if (hotbarObby == null) {
                    val invObby = Inventory.find(Items.OBSIDIAN)
                    if (invObby != null) {
                        Inventory.swap(invObby.slot, p.inventory.selectedSlot, true)
                    }
                }
            }

            // 2. Find nearby bed blocks
            val bedBlocks = findNearestBed()
            foundBed = bedBlocks
            if (bedBlocks.isEmpty()) {
                targetHit = null
                targetPos = null
                return@on
            }

            // 3. Collect core defense positions around the bed (1 layer shell)
            val corePositions = ArrayList<BlockPos>()
            for (bedPos in bedBlocks) {
                // Top of the bed
                val top = bedPos.above()
                if (level.getBlockState(top).canBeReplaced()) corePositions += top

                // Horizontal sides
                for (d in HORIZONTALS) {
                    val side = bedPos.relative(d)
                    if (side in bedBlocks) continue
                    if (level.getBlockState(side).canBeReplaced()) corePositions += side
                }
            }

            // Optional multi-layer: add positions directly above the core horizontal sides
            if (multiLayer.value) {
                val extraPositions = ArrayList<BlockPos>()
                for (core in corePositions) {
                    val above = core.above()
                    if (level.getBlockState(above).canBeReplaced() && above !in corePositions && above !in bedBlocks) {
                        extraPositions += above
                    }
                }
                corePositions.addAll(extraPositions)
            }

            val filtered = corePositions.distinct().filter {
                Vec3.atCenterOf(it).distanceTo(p.eyePosition) <= placeRange.value && !p.boundingBox.intersects(net.minecraft.world.phys.AABB(it))
            }

            if (filtered.isEmpty()) {
                targetHit = null
                targetPos = null
                if (autoDisable.value) disable()
                return@on
            }

            // 4. Find best placeable spot
            var bestHit: BlockHitResult? = null
            var bestPos: BlockPos? = null
            var bestDist = Double.MAX_VALUE
            val eye = p.eyePosition

            for (pos in filtered) {
                val hit = Placer.findHit(pos, -1, dev.horizon.client.module.Interact.Strict, dev.horizon.client.combat.IgnoreMode.ONLY_CRYSTALS, placeRange.value, placeRange.value, emptyList())
                    ?: Placer.findHit(pos, -1, dev.horizon.client.module.Interact.Vanilla, dev.horizon.client.combat.IgnoreMode.ONLY_CRYSTALS, placeRange.value, placeRange.value, emptyList())
                    ?: continue
                val d = eye.distanceToSqr(hit.location)
                if (d < bestDist) {
                    bestDist = d
                    bestHit = hit
                    bestPos = pos
                }
            }

            targetHit = bestHit
            targetPos = bestPos

            // 5. If aiming is aligned, place best defense block!
            val hit = targetHit ?: return@on
            val bp = targetPos ?: return@on
            if (delayTicks <= 0 && isAimingNear(p, hit.location)) {
                val blockSlot = findBestDefenseBlock()
                if (blockSlot != null) {
                    val prev = p.inventory.selectedSlot
                    if (prev != blockSlot.slot) Inventory.selectHotbar(blockSlot.slot)
                    mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, hit)
                    PlaceBoxes.add(bp, Theme.primary or (0xFF shl 24), Theme.primary or (0x44 shl 24), Animation.Fade, Render.BOTH)
                    delayTicks = placeDelay.value
                }
            }
        }

        on<MouseTurnEvent> {
            val p = mc.player ?: return@on
            val hit = targetHit ?: return@on
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.0 else ((now - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = now

            val eye = p.eyePosition
            val aim = hit.location
            val dx = aim.x - eye.x
            val dz = aim.z - eye.z
            val wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
            val wantPitch = -Math.toDegrees(atan2(aim.y - eye.y, hypot(dx, dz)))
            val errYaw = Mth.wrapDegrees(wantYaw - p.yRot)
            val errPitch = wantPitch - p.xRot

            val maxSpeed = speed.value.toDouble()
            val stiffness = 160.0
            val damping = 0.85
            val c = 2.0 * sqrt(stiffness) * damping

            vYaw = (vYaw + (stiffness * errYaw - c * vYaw) * dt).coerceIn(-maxSpeed, maxSpeed)
            vPitch = (vPitch + (stiffness * errPitch - c * vPitch) * dt).coerceIn(-maxSpeed, maxSpeed)

            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            val perCount = f * f * f * 8.0
            val step = perCount * 0.15
            carryX += vYaw * dt / step
            carryY += vPitch * dt / step
            val countsX = carryX.toInt()
            val countsY = carryY.toInt()
            carryX -= countsX
            carryY -= countsY
            if (countsX != 0 || countsY != 0) p.turn(countsX * perCount, countsY * perCount)
        }

        on<Render3DEvent> {
            val bp = targetPos ?: return@on
            val hit = targetHit ?: return@on
            PlaceBoxes.add(bp, Theme.primary or (0xFF shl 24), Theme.primary or (0x22 shl 24), Animation.Static, Render.BOTH)
        }
    }

    private fun findNearestBed(): List<BlockPos> {
        val p = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val r = searchRadius.value
        val center = BlockPos.containing(p.position())
        var nearestBedHead: BlockPos? = null
        var nearestDistSq = Double.MAX_VALUE

        for (x in center.x - r..center.x + r) for (y in center.y - 4..center.y + 4) for (z in center.z - r..center.z + r) {
            val pos = BlockPos(x, y, z)
            val state = level.getBlockState(pos)
            if (state.`is`(BlockTags.BEDS)) {
                // Never another team's bed: building onto it would only defend it for them.
                if (BedTeams.bedAt(level, pos)?.let { BedTeams.owner(it) } == BedTeams.Owner.ENEMY) continue
                val d = Vec3.atCenterOf(pos).distanceToSqr(p.eyePosition)
                if (d < nearestDistSq) {
                    nearestDistSq = d
                    nearestBedHead = pos
                }
            }
        }

        val headPos = nearestBedHead ?: return emptyList()
        val state = level.getBlockState(headPos)
        val result = ArrayList<BlockPos>(2)
        result += headPos

        // Find connected foot or head
        for (d in HORIZONTALS) {
            val other = headPos.relative(d)
            if (level.getBlockState(other).`is`(BlockTags.BEDS)) {
                result += other
                break
            }
        }
        return result
    }

    /**
     * Score hotbar defense blocks: Obsidian (100) > End Stone (80) > Wood Planks (60) > Wool (40) > any other solid block (20).
     */
    private fun findBestDefenseBlock(): dev.horizon.client.combat.Found? {
        val inv = mc.player?.inventory ?: return null
        var bestFound: dev.horizon.client.combat.Found? = null
        var bestScore = -1

        for (i in 0..8) {
            val stack = inv.getItem(i)
            if (stack.isEmpty || stack.item !is BlockItem) continue
            val score = scoreBlock(stack)
            if (score > bestScore) {
                bestScore = score
                bestFound = dev.horizon.client.combat.Found(i, stack)
            }
        }
        return bestFound
    }

    private fun scoreBlock(stack: ItemStack): Int {
        val item = stack.item
        return when {
            item == Items.OBSIDIAN -> 100
            item == Items.END_STONE -> 80
            stack.`is`(ItemTags.PLANKS) -> 60
            stack.`is`(ItemTags.WOOL) -> 40
            else -> 20
        }
    }

    private fun isAimingNear(p: net.minecraft.world.entity.player.Player, to: Vec3): Boolean {
        val eye = p.eyePosition
        val dx = to.x - eye.x
        val dz = to.z - eye.z
        val wantYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
        val wantPitch = -Math.toDegrees(atan2(to.y - eye.y, hypot(dx, dz)))
        val dy = abs(Mth.wrapDegrees(wantYaw - p.yRot))
        val dp = abs(wantPitch - p.xRot)
        return dy < 16.0 && dp < 16.0
    }

    private companion object {
        val HORIZONTALS = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    }
}
