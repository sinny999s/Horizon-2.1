package dev.horizon.client.module.attack

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.PlayerState
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max

/**
 * Autonomous Respawn Anchor combat for Overworld and End PvP. Places anchors near target players,
 * charges them with glowstone, and detonates them with explosion power 5.0, taking into account self damage, target
 * damage, line of sight, and friend protection.
 */
class AnchorAura : Module("anchor-aura", Categories.ATTACK) {
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

    val explodeGroup = group("detonate")
    val explodeDelay = int("detonate-delay", 0, 0..10, parent = explodeGroup.value)
    val explodeRange = float("detonate-range", 5.0f, 1.0f..6.0f, parent = explodeGroup.value)

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
    data class AnchorPlacement(val pos: BlockPos, val neighbor: BlockPos, val side: Direction, val dmg: DamageResult)
    data class ExistingAnchor(val pos: BlockPos, val charged: Boolean, val dmg: DamageResult)
    data class RenderRecord(val pos: BlockPos, val damage: Float, val self: Float)

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

    private fun isAnchorExplosionDimension(): Boolean {
        val path = mc.level?.dimension()?.identifier()?.path ?: return true
        return path != "the_nether"
    }

    private fun onTick() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (dimensionCheck.value && !isAnchorExplosionDimension()) {
            target = null
            return
        }

        // Auto refill anchors and glowstone to hotbar
        if (autoRefill.value) {
            if (Inventory.hotbarFind(Items.RESPAWN_ANCHOR) == null) {
                Inventory.find(Items.RESPAWN_ANCHOR)?.let { Inventory.swap(it.slot, player.inventory.selectedSlot, true) }
            }
            if (Inventory.hotbarFind(Items.GLOWSTONE) == null) {
                Inventory.find(Items.GLOWSTONE)?.let {
                    val emptySlot = (0..8).firstOrNull { s -> player.inventory.getItem(s).isEmpty }
                    if (emptySlot != null) Inventory.swap(it.slot, emptySlot, true)
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

        val existing = findBestExistingAnchor(t)
        if (existing != null) {
            if (existing.charged && explodeTimer.ticks >= explodeDelay.value) {
                detonateAnchor(existing.pos)
                return
            } else if (!existing.charged) {
                chargeAnchor(existing.pos)
                if (oneTickAction.value) detonateAnchor(existing.pos)
                return
            }
        }

        val placement = findBestPlacement(t)
        if (placement != null && placeTimer.ticks >= placeDelay.value) {
            placeAnchor(placement)
            if (oneTickAction.value) {
                chargeAnchor(placement.pos)
                detonateAnchor(placement.pos)
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

    private fun findBestExistingAnchor(t: Player): ExistingAnchor? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val center = BlockPos.containing(player.eyePosition)
        val r = ceil(explodeRange.value).toInt()
        val cands = ArrayList<ExistingAnchor>()

        for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
            val pos = BlockPos(x, y, z)
            val state = level.getBlockState(pos)
            if (!state.`is`(Blocks.RESPAWN_ANCHOR)) continue
            val hitPos = Vec3.atCenterOf(pos)
            if (hitPos.distanceTo(PlayerState.eye) > explodeRange.value) continue

            val charges = state.getValue(RespawnAnchorBlock.CHARGE)
            val dmg = evalDamage(hitPos, t) ?: continue
            cands += ExistingAnchor(pos, charges > 0, dmg)
        }

        if (cands.isEmpty()) return null
        var best = cands[0]
        for (c in cands) {
            if (c.dmg.damage > best.dmg.damage) best = c
            else if (minimizeSelfDamage.value && c.dmg.damage >= best.dmg.damage * 0.9f && c.dmg.self < best.dmg.self) best = c
        }
        return best
    }

    private fun findBestPlacement(t: Player): AnchorPlacement? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val center = BlockPos.containing(player.eyePosition)
        val r = ceil(placeRange.value).toInt()
        val cands = ArrayList<AnchorPlacement>()

        for (x in center.x - r..center.x + r) for (y in center.y - r..center.y + r) for (z in center.z - r..center.z + r) {
            val pos = BlockPos(x, y, z)
            if (!level.getBlockState(pos).canBeReplaced()) continue
            val hitCenter = Vec3.atCenterOf(pos)
            if (hitCenter.distanceTo(PlayerState.eye) > placeRange.value) continue

            // Must have a supporting neighbor to click on
            var foundNeighbor: BlockPos? = null
            var clickedSide: Direction? = null
            for (side in Direction.entries) {
                val neighbor = pos.relative(side)
                val state = level.getBlockState(neighbor)
                if (!state.canBeReplaced() && !state.isAir) {
                    foundNeighbor = neighbor
                    clickedSide = side.opposite
                    break
                }
            }
            if (foundNeighbor == null || clickedSide == null) continue

            // Collision check with player and friends
            val box = AABB(pos)
            if (player.boundingBox.intersects(box)) continue
            var friendBlocked = false
            for (other in level.players()) {
                if (other === player || !Friends.isFriend(other.gameProfile.name)) continue
                if (other.boundingBox.intersects(box)) {
                    friendBlocked = true
                    break
                }
            }
            if (friendBlocked) continue

            val dmg = evalDamage(hitCenter, t) ?: continue
            cands += AnchorPlacement(pos, foundNeighbor, clickedSide, dmg)
        }

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
        val lethal = dmg * lethalMultiplier.value >= (t.health + t.absorptionAmount)
        if (dmg < minDamage.value && !lethal) return null

        var self = Explosions.damage(center, player, 0, true, emptyList(), emptyList(), 5.0f)
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

    private fun placeAnchor(placement: AnchorPlacement) {
        val player = mc.player ?: return
        val holdingAnchor = player.mainHandItem.`is`(Items.RESPAWN_ANCHOR) || player.offhandItem.`is`(Items.RESPAWN_ANCHOR)
        val hotbarAnchor = Inventory.hotbarFind(Items.RESPAWN_ANCHOR)
        val invAnchor = if (switch.value == Switch.INVENTORY) Inventory.find(Items.RESPAWN_ANCHOR) else null

        if (!holdingAnchor && hotbarAnchor == null && invAnchor == null) return

        val clickPos = Vec3.atCenterOf(placement.neighbor).add(
            placement.side.stepX * 0.5,
            placement.side.stepY * 0.5,
            placement.side.stepZ * 0.5
        )
        val hit = BlockHitResult(clickPos, placement.side, placement.neighbor, false)

        if (rotate.value) {
            rotationTarget.set(clickPos)
            rotationTimer.reset()
        }

        val action = {
            var prevSlot = -1
            if (!holdingAnchor) {
                when (switch.value) {
                    Switch.NORMAL, Switch.SILENT -> {
                        if (hotbarAnchor != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.selectHotbar(hotbarAnchor.slot)
                        }
                    }
                    Switch.INVENTORY -> {
                        if (invAnchor != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.swap(invAnchor.slot, player.inventory.selectedSlot, true)
                        } else if (hotbarAnchor != null) {
                            prevSlot = player.inventory.selectedSlot
                            Inventory.selectHotbar(hotbarAnchor.slot)
                        }
                    }
                    Switch.NONE -> {}
                }
            }

            val hand = if (player.offhandItem.`is`(Items.RESPAWN_ANCHOR)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(hand, hit, seq) }
            player.swing(hand, SwingAnimation.DEFAULT, false)

            if (prevSlot != -1 && switch.value == Switch.SILENT) {
                Inventory.selectHotbar(prevSlot)
            }

            renderRecord = RenderRecord(placement.pos, placement.dmg.damage, placement.dmg.self)
            fade.restart(1f)
            placeTimer.reset()
        }

        if (timing.value == Timing.SEQUENTIAL) {
            deferredPlace = action
        } else {
            action()
        }
    }

    private fun chargeAnchor(pos: BlockPos) {
        val player = mc.player ?: return
        val holdingGlow = player.mainHandItem.`is`(Items.GLOWSTONE) || player.offhandItem.`is`(Items.GLOWSTONE)
        val hotbarGlow = Inventory.hotbarFind(Items.GLOWSTONE)
        val invGlow = if (switch.value == Switch.INVENTORY) Inventory.find(Items.GLOWSTONE) else null

        if (!holdingGlow && hotbarGlow == null && invGlow == null) return

        val clickPos = Vec3.atCenterOf(pos)
        val hit = BlockHitResult(clickPos, Direction.UP, pos, false)

        if (rotate.value) {
            rotationTarget.set(clickPos)
            rotationTimer.reset()
        }

        var prevSlot = -1
        if (!holdingGlow) {
            when (switch.value) {
                Switch.NORMAL, Switch.SILENT -> {
                    if (hotbarGlow != null) {
                        prevSlot = player.inventory.selectedSlot
                        Inventory.selectHotbar(hotbarGlow.slot)
                    }
                }
                Switch.INVENTORY -> {
                    if (invGlow != null) {
                        prevSlot = player.inventory.selectedSlot
                        Inventory.swap(invGlow.slot, player.inventory.selectedSlot, true)
                    } else if (hotbarGlow != null) {
                        prevSlot = player.inventory.selectedSlot
                        Inventory.selectHotbar(hotbarGlow.slot)
                    }
                }
                Switch.NONE -> {}
            }
        }

        val hand = if (player.offhandItem.`is`(Items.GLOWSTONE)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(hand, hit, seq) }
        player.swing(hand, SwingAnimation.DEFAULT, false)

        if (prevSlot != -1 && switch.value == Switch.SILENT) {
            Inventory.selectHotbar(prevSlot)
        }
    }

    private fun detonateAnchor(pos: BlockPos) {
        val player = mc.player ?: return
        val clickPos = Vec3.atCenterOf(pos)
        val hit = BlockHitResult(clickPos, Direction.UP, pos, false)

        if (rotate.value) {
            rotationTarget.set(clickPos)
            rotationTimer.reset()
        }

        val action = {
            var prevSlot = -1
            // Must NOT be holding glowstone when right-clicking to detonate
            if (player.mainHandItem.`is`(Items.GLOWSTONE)) {
                val nonGlow = (0..8).firstOrNull { s -> !player.inventory.getItem(s).`is`(Items.GLOWSTONE) }
                if (nonGlow != null) {
                    prevSlot = player.inventory.selectedSlot
                    Inventory.selectHotbar(nonGlow)
                }
            }

            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, seq) }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)

            if (prevSlot != -1 && switch.value == Switch.SILENT) {
                Inventory.selectHotbar(prevSlot)
            }

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

        val box = AABB(rec.pos)
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
            World3D.label(text, box.center.add(0.0, 0.5, 0.0), PlaceBoxes.withAlpha(Theme.text, fade.value))
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
