package dev.horizon.client.module.rage

import dev.horizon.client.combat.GrimRequest
import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.containerClick
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostTasksEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.player.AirPlace
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Every tick (PlayerUpdate priority -10) finds the block under the player (or at the kept
 * height) and a neighbour to click, turns to a point on that face and, once the rotation is through ([timing]: queued,
 * instant, or a tick later), places from the hotbar ([switch]) or the offhand. Tower jumps straight up while jump is
 * held standing still, safe-walk sneaks at edges, telly skips placing while jumping, grim-block-fly holds incoming
 * packets back 500 ms and places with the offhand swapped in.
 */
class Scaffold : Module("scaffold", Categories.RAGE) {
    enum class Timing { SEQUENTIAL, VANILLA, GRIM }
    @Suppress("EnumEntryName")
    enum class Switch { Normal, Silent, Inventory, None }

    val timing = enum("timing", Timing.GRIM)
    val switch = enum("switch", Switch.Silent)
    val tower = bool("tower", true)
    val safeWalk = bool("safe-walk", false)
    val allowShift = bool("allow-shift", false) { !safeWalk.value }
    val legit = bool("legit", true)
    val saveAngles = bool("save-angles", true)
    val telly = bool("telly", false)
    val clamp = bool("clamp", true)
    val airPlace = bool("air-place", false)
    val grimBlockFly = bool("grim-block-fly", false)
    val keepHeight = bool("keep-height", false)
    val rotate = bool("rotate", true)
    val grimSilent = bool("grim-silent", false)

    /** The block to click and the face. */
    private class Placement(val pos: BlockPos, val face: Direction)

    /** A held-back packet and when it came. */
    private class Held(val packet: Packet<*>, val time: Long)

    private val towerTimer = MsTimer()

    /** The kept height (-999 for none). */
    private var height = -999

    /** The last rotation to the click point. */
    private var lastRotation: Rotation? = null

    /** This module pressed sneak for safe-walk. */
    private var sneaking = false

    private val held = ConcurrentLinkedQueue<Held>()
    private val due = ConcurrentLinkedQueue<Packet<*>>()

    override fun onEnable() {
        height = -999
    }

    override fun onDisable() {
        if (sneaking && !Keys.down(340)) mc.options.keyShift.isDown = false
        flushHeld()
    }

    /** A placeable block that stays (not replaceable, not falling). */
    private fun placeable(s: ItemStack): Boolean {
        val item = s.item as? BlockItem ?: return false
        if (item.block.defaultBlockState().canBeReplaced()) return false
        return item.block !is FallingBlock
    }

    /** Whether the block in [slot] (-2 offhand, -1 none) is a full cube. */
    private fun fullBlock(slot: Int): Boolean {
        val p = mc.player ?: return true
        val level = mc.level ?: return true
        val stack = when (slot) {
            -2 -> p.offhandItem
            -1 -> return true
            else -> p.inventory.getItem(slot)
        }
        val item = stack.item as? BlockItem ?: return true
        return Block.isShapeFullBlock(item.block.defaultBlockState().getCollisionShape(level, BlockPos.ZERO))
    }

    /**
     * The slot to place from: -2 offhand, the selected slot, -1 none. With [doSwitch] it also switches
     * (Normal/Silent select the hotbar block; Inventory sends a swap click with button -1, which the server
     * ignores).
     */
    private fun blockSlot(doSwitch: Boolean): Int {
        val p = mc.player ?: return -1
        if (placeable(p.offhandItem)) return -2
        if (placeable(p.mainHandItem)) return p.inventory.selectedSlot
        val hotbar = Inventory.hotbarFind { placeable(it) }
        val inv = Inventory.find { placeable(it) }
        return when (switch.value) {
            Switch.Inventory -> {
                if (inv == null) return -1
                if (doSwitch) containerClick(inv.slot, -1, ContainerInput.SWAP, true)
                p.inventory.selectedSlot
            }
            Switch.Normal, Switch.Silent -> {
                if (hotbar == null) return -1
                val prev = p.inventory.selectedSlot
                if (doSwitch) Inventory.selectHotbar(hotbar.slot)
                prev
            }
            Switch.None -> -1
        }
    }

    /** Switch back after placing (Silent re-selects, Inventory swaps back, Normal stays). */
    private fun switchBack(slot: Int) {
        val p = mc.player ?: return
        if (slot == -2 || slot == -1) return
        when (switch.value) {
            Switch.Inventory -> containerClick(slot, p.inventory.selectedSlot, ContainerInput.SWAP, true)
            Switch.Silent -> Inventory.selectHotbar(slot)
            else -> {}
        }
    }

    /** A solid neighbour of [pos] to click, below first. */
    private fun neighbour(pos: BlockPos): Placement? {
        val level = mc.level ?: return null
        for ((face, off) in NEIGHBOURS) {
            val n = pos.offset(off)
            if (!level.getBlockState(n).canBeReplaced()) return Placement(n, face)
        }
        return null
    }

    /** A click for [pos] or a nearby spot under the player, when [pos] is free. */
    private fun findPlacement(pos: BlockPos): Placement? {
        val level = mc.level ?: return null
        if (!level.getBlockState(pos).canBeReplaced()) return null
        for (off in SEARCH) neighbour(pos.offset(off))?.let { return it }
        return null
    }

    private fun tellyPause(): Boolean = mc.options.keyJump.isDown && PlayerState.fallDistance <= 0.0 && telly.value

    /** Runs at PlayerUpdate priority -10. */
    private fun tick() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (tellyPause()) return
        val slot = blockSlot(false)
        if (!safeWalk.value && p.isShiftKeyDown && !allowShift.value) return
        if (slot == -1) return
        if (mc.options.keyJump.isDown && !Movement.moving()) height = floor(p.y - 1).toInt()
        if (p.position().y - height < 0) height = -999
        val y = if (fullBlock(slot)) p.y else ceil(p.y)
        val pos = if ((keepHeight.value || telly.value) && height != -999) BlockPos.containing(p.x, height.toDouble(), p.z)
        else BlockPos.containing(p.x, y, p.z).below()
        val place = if (airPlace.value) Placement(pos, Direction.DOWN) else findPlacement(pos)
        if (safeWalk.value && Movement.moving()) {
            if (!p.isShiftKeyDown && level.getBlockState(pos).canBeReplaced()) {
                mc.options.keyShift.isDown = true
                sneaking = true
            }
            if (p.isShiftKeyDown && !level.getBlockState(pos).canBeReplaced()) {
                mc.options.keyShift.isDown = false
                sneaking = false
            }
        }
        val inset = if (timing.value == Timing.VANILLA) -0.1 else -0.25
        if (place != null &&
            !(legit.value && level.getBlockCollisions(p, p.boundingBox.inflate(inset, 0.0, inset).move(0.0, -0.1, 0.0)).iterator().hasNext())
        ) {
            val center = BlockHitResult(Vec3.atCenterOf(place.pos).add(Vec3(place.face.step()).scale(0.5)), place.face, place.pos, false)
            val yOff = if (p.y - floor(p.y) - 0.5 == 0.0) 0.39 else 0.82 + Rng.double(0.0, 0.16)
            val bp = center.blockPos
            val x = if (clamp.value) (p.x + Rng.double(-0.01, 0.01)).coerceIn(bp.x.toDouble(), bp.x + 1.0) else center.location.x
            val z = if (clamp.value) (p.z + Rng.double(-0.01, 0.01)).coerceIn(bp.z.toDouble(), bp.z + 1.0) else center.location.z
            val hit = BlockHitResult(Vec3(x, place.pos.y + yOff, z), place.face, place.pos, false)
            val rot = Rotation.lookAt(hit.location)
            if (rotate.value && grimSilent.value) Packets.sendRotation(rot.yaw, rot.pitch)
            val use = if (rotate.value && !grimSilent.value) rot else Rotation(p.yRot, p.xRot)
            val action = { place(hit) }
            RotationManager.queue(
                when (timing.value) {
                    Timing.SEQUENTIAL -> RotationRequest(use, 150, action)
                    Timing.VANILLA -> InstantRequest(use, 150, action)
                    Timing.GRIM -> GrimRequest(use, 150, action)
                }
            )
            lastRotation = rot
            return
        }
        val last = lastRotation
        if (last != null && saveAngles.value) {
            RotationManager.rotate(if (rotate.value && !grimSilent.value) last else Rotation(p.yRot, p.xRot), 150)
        }
    }

    /** The click, run once the rotation is through. */
    private fun place(hit: BlockHitResult) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (tellyPause()) return
        val slot = blockSlot(true)
        if (slot == -1) return
        if (mc.options.keyJump.isDown && !Movement.moving() && tower.value) {
            p.setDeltaMovement(p.deltaMovement.x, if (towerTimer.passedAndReset(1500)) -0.28 else 0.42, p.deltaMovement.z)
        } else {
            towerTimer.reset()
        }
        val sneak = Placer.isInteractable(level.getBlockState(hit.blockPos).block) && !p.isShiftKeyDown
        if (sneak) PlayerState.setSneak(true)
        val hand = if (slot != -2) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        // 26.3 has no swing packet: the swings below only animate (and the packet after useItemOn is dropped).
        if (airPlace.value && Modules.get<AirPlace>().enabled && Modules.get<AirPlace>().n2b2t.value) {
            mc.gameMode?.useItem(p, hand)
            Packets.sendSequenced { seq -> ServerboundUseItemPacket(hand, seq, Rotation.lastYaw, Rotation.lastPitch) }
            Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
            Packets.sendSequenced { seq -> ServerboundUseItemOnPacket(hand, hit, seq) }
            p.swing(hand, SwingAnimation.DEFAULT, false)
            Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
            p.swing(hand, SwingAnimation.DEFAULT, false)
        } else {
            val count = p.mainHandItem.count
            if (grimBlockFly.value) {
                Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
            }
            mc.gameMode?.useItemOn(p, hand, hit)
            if (grimBlockFly.value) {
                Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
                p.mainHandItem.count = count
            }
        }
        height = hit.blockPos.y
        if (sneak) PlayerState.setSneak(false)
        switchBack(slot)
        if (rotate.value && grimSilent.value) Packets.sendRotation(p.yRot, p.xRot)
    }

    /** Handle every held-back packet now. */
    private fun flushHeld() {
        held.removeIf {
            handle(it.packet)
            true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(packet: Packet<*>) {
        val listener = mc.connection ?: return
        runCatching { (packet as Packet<ClientGamePacketListener>).handle(listener) }
    }

    init {
        on<PlayerUpdateEvent>(-10) { tick() }
        on<ReceivePacketEvent> { e ->
            if (!grimBlockFly.value) return@on
            val pk = e.packet
            if (pk is ServerboundChatPacket || pk is ClientboundSystemChatPacket || pk is ClientboundSetTimePacket ||
                pk is ServerboundChatCommandPacket || pk is ClientboundSoundPacket || pk is ClientboundStopSoundPacket ||
                pk is ClientboundSetActionBarTextPacket || pk is ClientboundSetHealthPacket || pk is ClientboundBossEventPacket
            ) return@on
            if (pk is ClientboundPlayerPositionPacket || pk is ClientboundDisconnectPacket) {
                flushHeld()
                return@on
            }
            held.add(Held(pk, System.currentTimeMillis()))
            e.cancel()
        }
        on<PostTasksEvent> {
            if (mc.level == null || mc.player == null) {
                held.clear()
                return@on
            }
            val cutoff = System.currentTimeMillis() - 500
            held.removeIf {
                val old = it.time <= cutoff
                if (old) due.add(it.packet)
                old
            }
            due.removeIf {
                handle(it)
                true
            }
        }
    }

    private companion object {
        /** Face to click → offset of the block clicked. */
        val NEIGHBOURS = listOf(
            Direction.UP to BlockPos(0, -1, 0), Direction.DOWN to BlockPos(0, 1, 0), Direction.EAST to BlockPos(-1, 0, 0),
            Direction.WEST to BlockPos(1, 0, 0), Direction.NORTH to BlockPos(0, 0, 1), Direction.SOUTH to BlockPos(0, 0, -1),
        )

        /** Spots around the target tried in order. */
        val SEARCH = arrayOf(
            BlockPos(0, 0, 0), BlockPos(-1, 0, 0), BlockPos(1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1),
            BlockPos(-2, 0, 0), BlockPos(2, 0, 0), BlockPos(0, 0, 2), BlockPos(0, 0, -2), BlockPos(0, -1, 0),
            BlockPos(1, -1, 0), BlockPos(-1, -1, 0), BlockPos(0, -1, 1), BlockPos(0, -1, -1),
        )
    }
}
