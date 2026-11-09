package dev.horizon.client.module.attack

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Predictor
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.TargetFinder
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.config.Friends
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostSyncEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Autonomous bed combat for Nether and End PvP. Places beds near target players and detonates
 * them with explosion power 5.0, taking into account self damage, target damage, line of sight, and friend protection.
 */
class BedAura : Module("bed-aura", Categories.ATTACK) {
    enum class Timing { VANILLA, SEQUENTIAL }
    enum class Switch { NONE, NORMAL, SILENT, INVENTORY }
    enum class TargetSort { DISTANCE, FOV, HEALTH }
    enum class FriendProtection { NONE, ONLY_LETHAL, FULL }
    enum class RenderMode { SHADER, BOX, OFF }

    val main = group("main")
    val timing = enum("timing", Timing.VANILLA, parent = main.value)
    val oneTickAction = bool("one-tick", true, parent = main.value)
    val rotate = bool("rotate", true, parent = main.value)
    val dimensionCheck = bool("dimension-check", true, parent = main.value)
    val autoRefill = bool("auto-refill", true, parent = main.value)

    val placeGroup = group("place")
    val placeDelay = int("place-delay", 0, 0..10, parent = placeGroup.value)
    val placeRange = float("place-range", 4.5f, 1.0f..6.0f, parent = placeGroup.value)

    val explodeGroup = group("explode")
    val explodeDelay = int("explode-delay", 0, 0..10, parent = explodeGroup.value)
    val explodeRange = float("explode-range", 5.0f, 1.0f..6.0f, parent = explodeGroup.value)

    val damages = group("damages")
    val minDamage = float("min-damage", 6.0f, 1.0f..20.0f, parent = damages.value)
    val maxSelfDamage = float("max-self-damage", 8.0f, 1.0f..20.0f, parent = damages.value)
    val minDamageFactor = float("min-damage-factor", 1.0f, 0.1f..5.0f, parent = damages.value)
    val lethalMultiplier = int("lethal-multiplier", 1, 1..3, parent = damages.value)
    val overrideSelfDamage = bool("override-self-damage", true, parent = damages.value)
    val friendProtection = enum("friend-protection", FriendProtection.ONLY_LETHAL, parent = damages.value)
    val minimizeSelfDamage = bool("minimize-self-damage", true, parent = damages.value)

    val swapGroup = group("swap")
    val switch = enum("switch", Switch.SILENT, parent = swapGroup.value)

    val targeting = group("targeting")
    val targetSort = enum("target-sort", TargetSort.DISTANCE, parent = targeting.value)
    val ignoreNaked = bool("ignore-naked", false, parent = targeting.value)
    val ignoreCreative = bool("ignore-creative", true, parent = targeting.value)

    val render = group("render")
    val renderMode = enum("render-mode", RenderMode.SHADER, parent = render.value)
    val alpha = float("alpha", 0.4f, 0.0f..1.0f, parent = render.value)
    val showText = bool("text", true, parent = render.value)

    private val placeTimer = TickTimer()
    private val explodeTimer = TickTimer()
    private val rotationTimer = MsTimer()
    private val rotationTarget = AtomicReference<Vec3?>(null)

    private var target: Player? = null
    private var renderRecord: RenderRecord? = null
    private val fade = Fade()

    private var deferredExplode: (() -> Unit)? = null
    private var deferredPlace: (() -> Unit)? = null

    data class DamageResult(val damage: Float, val self: Float, val override: Boolean, val lethal: Boolean)
    data class BedPlacement(val foot: BlockPos, val dir: Direction, val head: BlockPos, val dmg: DamageResult)
    data class ExistingBed(val pos: BlockPos, val dmg: DamageResult)
    data class RenderRecord(val foot: BlockPos, val head: BlockPos, val damage: Float, val self: Float)

    init {
        on<PlayerUpdateEvent> { onTick() }
        on<PostSyncEvent> {
            deferredExplode?.invoke()
            deferredExplode = null
            deferredPlace?.invoke()
            deferredPlace = null
        }
        on<Render3DEvent> { render() }
    }

    override fun onDisable() {
        target = null
        renderRecord = null
        rotationTarget.set(null)
        deferredExplode = null
        deferredPlace = null
    }

    private fun isBedDimension(): Boolean {
        val path = mc.level?.dimension()?.identifier()?.path ?: return false
        return path == "the_nether" || path == "the_end"
    }

    private fun onTick() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (dimensionCheck.value && !isBedDimension()) {
            target = null
            return
        }

        // Auto refill bed to hotbar from inventory if hotbar is empty of beds
        if (autoRefill.value) {
            val hotbarBed = Inventory.hotbarFind { it.`is`(ItemTags.BEDS) }
            if (hotbarBed == null) {
                val invBed = Inventory.find { it.`is`(ItemTags.BEDS) }
                if (invBed != null) {
                    Inventory.swap(invBed.slot, player.inventory.selectedSlot, true)
                }
            }
        }

        val searchRange = max(placeRange.value, explodeRange.value) + 8.0
        val t = TargetFinder.find(searchRange, TargetFinder.Sort.valueOf(targetSort.value.name), ignoreNaked.value, ignoreCreative.value)
        target = t
        if (t == null) {
            renderRecord = null
            return
        }

        // println("[BED_DEBUG] onTick target=${t.gameProfile.name} isBedDim=${isBedDimension()} dimCheck=${dimensionCheck.value}")
        val existingBed = findBestExistingBed(t)
        val bestPlacement = if (existingBed == null || oneTickAction.value) findBestPlacement(t) else null

        if (existingBed != null && explodeTimer.ticks >= explodeDelay.value) {
            explodeBed(existingBed)
            if (oneTickAction.value && bestPlacement != null && placeTimer.ticks >= placeDelay.value) {
                placeBed(bestPlacement)
            }
            return
        }

        if (bestPlacement != null && placeTimer.ticks >= placeDelay.value) {
            placeBed(bestPlacement)
            if (oneTickAction.value) {
                // Instantly detonate placed bed
                val headPos = bestPlacement.head
                val dmg = bestPlacement.dmg
                explodeBed(ExistingBed(headPos, dmg))
            }
        }

        if (rotate.value) {
            val rotPos = rotationTarget.get()
            if (rotPos != null) {
                RotationManager.queue(RotationRequest(Rotation.lookAt(rotPos), 140) {})
                if (rotationTimer.passed(500)) rotationTarget.set(null)
            }
        }
    }

    private fun findBestExistingBed(t: Player): ExistingBed? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val center = BlockPos.containing(player.eyePosition)
        val r = ceil(explodeRange.value).toInt()
        val cands = ArrayList<ExistingBed>()

        for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
            val pos = BlockPos(x, y, z)
            val state = level.getBlockState(pos)
            if (!state.`is`(BlockTags.BEDS)) continue
            val hitPos = Vec3.atCenterOf(pos)
            if (hitPos.distanceTo(PlayerState.eye) > explodeRange.value) continue

            val dmg = evalDamage(hitPos, t) ?: continue
            cands += ExistingBed(pos, dmg)
        }

        // println("[BED_DEBUG] findBestPlacement cands=${cands.size}")
        if (cands.isEmpty()) return null
        var best = cands[0]
        for (c in cands) {
            if (c.dmg.damage > best.dmg.damage) best = c
            else if (minimizeSelfDamage.value && c.dmg.damage >= best.dmg.damage * 0.9f && c.dmg.self < best.dmg.self) best = c
        }
        return best
    }

    private fun findBestPlacement(t: Player): BedPlacement? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val center = BlockPos.containing(player.eyePosition)
        val r = ceil(placeRange.value).toInt()
        val cands = ArrayList<BedPlacement>()
        val horizontalDirs = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

        for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
            val foot = BlockPos(x, y, z)
            if (!level.getBlockState(foot).canBeReplaced()) continue
            val belowFoot = foot.below()
            if (level.getBlockState(belowFoot).canBeReplaced() || level.getBlockState(belowFoot).isAir) continue
            if (Vec3.atCenterOf(foot).distanceTo(PlayerState.eye) > placeRange.value) continue

            for (d in horizontalDirs) {
                val head = foot.relative(d)
                if (Vec3.atCenterOf(head).distanceTo(PlayerState.eye) > placeRange.value) continue
                if (!level.getBlockState(head).canBeReplaced()) continue
                if (level.getBlockState(head.below()).canBeReplaced() || level.getBlockState(head.below()).isAir) continue

                // Check collision with player bounding box
                val footBox = AABB(foot)
                val headBox = AABB(head)
                if (player.boundingBox.intersects(footBox) || player.boundingBox.intersects(headBox)) continue

                // Check collision with friends
                var friendBlocked = false
                for (other in level.players()) {
                    if (other === player || !Friends.isFriend(other.gameProfile.name)) continue
                    if (other.boundingBox.intersects(footBox) || other.boundingBox.intersects(headBox)) {
                        friendBlocked = true
                        break
                    }
                }
                if (friendBlocked) continue

                val explosionCenter = Vec3.atCenterOf(head)
                val dmg = evalDamage(explosionCenter, t) ?: continue
                cands += BedPlacement(foot, d, head, dmg)
            }
        }

        // println("[BED_DEBUG] findBestPlacement cands=${cands.size}")
        if (cands.isEmpty()) return null
        var best = cands[0]
        for (c in cands) {
            if (c.dmg.damage > best.dmg.damage) best = c
            else if (minimizeSelfDamage.value && c.dmg.damage >= best.dmg.damage * 0.9f && c.dmg.self < best.dmg.self) best = c
        }
        return best
    }

    private fun evalDamage(center: Vec3, t: Player): DamageResult? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null

        val dmg = Explosions.damage(center, t, 0, true, emptyList(), emptyList(), 5.0f)
        var self = Explosions.damage(center, player, 0, true, emptyList(), emptyList(), 5.0f)
        // println("[BED_DEBUG] eval center=$center dmg=$dmg self=$self")
        val lethal = dmg * lethalMultiplier.value >= (t.health + t.absorptionAmount)
        if (dmg < minDamage.value && !lethal) return null
        val overrideSelf = overrideSelfDamage.value && lethal && self < (player.health + player.absorptionAmount)
        if (!overrideSelf) {
            if (self > maxSelfDamage.value) return null
            if (self > player.health + player.absorptionAmount) return null
            if (dmg / max(self, 0.5f) < minDamageFactor.value) return null
        }

        if (friendProtection.value != FriendProtection.NONE) {
            for (f in level.players()) {
                if (f === player || !f.isAlive || !Friends.isFriend(f.gameProfile.name)) continue
                val fd = Explosions.damage(center, f, 0, true, emptyList(), emptyList(), 5.0f)
                if (friendProtection.value == FriendProtection.FULL && fd > maxSelfDamage.value) return null
                if (fd >= f.health + f.absorptionAmount) return null
            }
        }

        return DamageResult(dmg, self, overrideSelf, lethal)
    }

    private fun directionYaw(d: Direction): Float = when (d) {
        Direction.SOUTH -> 0.0f
        Direction.WEST -> 90.0f
        Direction.NORTH -> 180.0f
        Direction.EAST -> -90.0f
        else -> 0.0f
    }

    private fun placeBed(placement: BedPlacement) {
        val player = mc.player ?: return
        val holdingBed = player.mainHandItem.`is`(ItemTags.BEDS) || player.offhandItem.`is`(ItemTags.BEDS)
        val hotbarBed = Inventory.hotbarFind { it.`is`(ItemTags.BEDS) }
        val invBed = if (switch.value == Switch.INVENTORY) Inventory.find { it.`is`(ItemTags.BEDS) } else null

        if (!holdingBed && hotbarBed == null && invBed == null) return

        val yaw = directionYaw(placement.dir)
        val clickPos = Vec3.atCenterOf(placement.foot.below()).add(0.0, 0.5, 0.0)
        val hit = BlockHitResult(clickPos, Direction.UP, placement.foot.below(), false)

        if (rotate.value) {
            val look = Rotation.lookAt(clickPos)
            RotationManager.queue(RotationRequest(Rotation(yaw, look.pitch), 140) {})
        }

        val action = {
            var prevSlot = -1
            if (!holdingBed) {
                when (switch.value) {
                    Switch.NORMAL, Switch.SILENT -> {
                        if (hotbarBed != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.selectHotbar(hotbarBed.slot)
                        }
                    }
                    Switch.INVENTORY -> {
                        if (invBed != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.swap(invBed.slot, player.inventory.selectedSlot, true)
                        } else if (hotbarBed != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.selectHotbar(hotbarBed.slot)
                        }
                    }
                    Switch.NONE -> {}
                }
            }

            val hand = if (player.offhandItem.`is`(ItemTags.BEDS)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(hand, hit, seq) }
            player.swing(hand, SwingAnimation.DEFAULT, false)

            if (prevSlot != -1 && switch.value == Switch.SILENT) {
                Inventory.selectHotbar(prevSlot)
            }

            renderRecord = RenderRecord(placement.foot, placement.head, placement.dmg.damage, placement.dmg.self)
            fade.restart(1f)
            placeTimer.reset()
        }

        if (timing.value == Timing.SEQUENTIAL) {
            deferredPlace = action
        } else {
            action()
        }
    }

    private fun explodeBed(bed: ExistingBed) {
        val player = mc.player ?: return
        val clickPos = Vec3.atCenterOf(bed.pos)
        val hit = BlockHitResult(clickPos, Direction.UP, bed.pos, false)

        if (rotate.value) {
            RotationManager.queue(RotationRequest(Rotation.lookAt(clickPos), 140) {})
        }

        val action = {
            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
            explodeTimer.reset()
        }

        if (timing.value == Timing.SEQUENTIAL) {
            deferredExplode = action
        } else {
            action()
        }
    }

    private fun render() {
        val rec = renderRecord ?: return
        if (renderMode.value == RenderMode.OFF) return
        fade.update(0f)
        if (fade.value <= 0.01f) return

        val minX = min(rec.foot.x, rec.head.x).toDouble()
        val minY = rec.foot.y.toDouble()
        val minZ = min(rec.foot.z, rec.head.z).toDouble()
        val maxX = (max(rec.foot.x, rec.head.x) + 1).toDouble()
        val maxY = rec.foot.y + 0.5625
        val maxZ = (max(rec.foot.z, rec.head.z) + 1).toDouble()
        val box = AABB(minX, minY, minZ, maxX, maxY, maxZ)

        val alphaVal = alpha.value * fade.value
        when (renderMode.value) {
            RenderMode.SHADER -> World3D.shaderBox(box, PlaceBoxes.withAlpha(Theme.primary, alphaVal))
            RenderMode.BOX -> {
                World3D.fill(box, PlaceBoxes.withAlpha(Theme.primary, alphaVal))
                World3D.outline(box, PlaceBoxes.withAlpha(Theme.primary, fade.value))
            }
            RenderMode.OFF -> {}
        }

        if (showText.value) {
            val text = "${fmt(rec.damage)} / ${fmt(rec.self)}"
            World3D.label(text, box.center.add(0.0, 0.4, 0.0), PlaceBoxes.withAlpha(Theme.text, fade.value))
        }
    }

    private fun fmt(f: Float): Float =
        if (f.isNaN() || f.isInfinite()) 1.0f else BigDecimal(f.toDouble()).setScale(1, RoundingMode.HALF_UP).toFloat()

    class Fade {
        private var start = 0f
        private var target = 0f
        private var time = 0L
        var value = 0f
            private set

        fun restart(from: Float) {
            start = from
            value = from
            time = System.currentTimeMillis()
        }

        fun update(to: Float) {
            if (to != target) {
                start = value
                target = to
                time = System.currentTimeMillis()
            }
            val e = System.currentTimeMillis() - time
            value = if (e > 800) target else start + (target - start) * Easing.OUT_BACK.apply(e / 800.0).toFloat()
        }
    }
}
