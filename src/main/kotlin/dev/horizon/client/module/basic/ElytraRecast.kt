package dev.horizon.client.module.basic

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.Slots
import dev.horizon.client.event.AfterElytraEvent
import dev.horizon.client.event.FallFlyingEvent
import dev.horizon.client.event.JumpEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreElytraEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.SetPoseEvent
import dev.horizon.client.event.ShouldStopSprintingEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.mixin.basic.LivingEntityBasicAccessor
import dev.horizon.client.mixin.basic.LocalPlayerBasicAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

/**
 * Holds forward, jump and sprint and starts gliding again whenever gliding stops, so
 * the elytra keeps flying low over the ground. With grim the server rotation and the travel direction are locked to a
 * yaw and [pitch]; grim-durability swaps the elytra on only for the start-gliding packet. Server pings, pongs and
 * movement packets are held back at certain heights (the "tunnels" handling).
 */
class ElytraRecast : Module("elytra-recast", Categories.BASIC) {
    enum class Bypass { SEMI, FULL }

    val grim = bool("grim", true)
    val tunnelsOffset = bool("tunnels-offset", true)
    val bypass = enum("bypass", Bypass.SEMI) { grim.value }
    val grimDurability = bool("grim-durability", true) { grim.value }
    val changePitch = bool("change-pitch", true) { !grim.value }
    val pitch = float("pitch", 87.0f, 0.0f..90.0f) { !grim.value && changePitch.value }
    val allowBroken = bool("allow-broken", true) { !grim.value }

    // The rotation around each travel step (for the elytra part, and for the whole travel).
    private var elytraPitch = 0f
    private var elytraYaw = 0f
    private var travelYaw = 0f
    private var travelPitch = 0f

    /** The yaw grim mode flies along: taken on enable, after a teleport and whenever pongs are let go. */
    private var lockedYaw = 0f

    /** Gliding as far as this module knows. */
    @Volatile
    private var recasting = false

    /** Held packets are being sent: the send handler leaves them alone. */
    private var releasing = false

    /** Ticks left after a teleport during which it stands still (60 per teleport). */
    @Volatile
    private var settle = 0

    /** Already caught at the top of this jump; cleared on the ground. */
    private var primed = false

    /** The movement packets were going down last, and the last packet Y. */
    private var descending = false
    private var lastY = 0.0

    private val held: Queue<Packet<*>> = ConcurrentLinkedQueue()
    private val pings: Queue<ClientboundPingPacket> = ConcurrentLinkedQueue()
    private val pongs: Queue<ServerboundPongPacket> = ConcurrentLinkedQueue()
    private val moves: Queue<ServerboundMovePlayerPacket> = ConcurrentLinkedQueue()

    init {
        on<PreElytraEvent>(-10) {
            val p = mc.player ?: return@on
            elytraYaw = p.yRot
            if (!(changePitch.value || grim.value)) return@on
            if (!p.isFallFlying) return@on
            elytraPitch = p.xRot
            p.xRot = pitch.value
            if (grim.value) p.yRot = lockedYaw
        }
        on<AfterElytraEvent>(-10) {
            val p = mc.player ?: return@on
            if (!(changePitch.value || grim.value)) return@on
            if (!p.isFallFlying) return@on
            p.xRot = elytraPitch
            p.yRot = elytraYaw
        }
        on<TravelEvent> { e ->
            val p = mc.player ?: return@on
            if (!grim.value || mc.level == null) return@on
            if (e.pre) {
                travelYaw = p.yRot
                travelPitch = p.xRot
                p.xRot = pitch.value
                p.yRot = lockedYaw
            } else {
                p.yRot = travelYaw
                p.xRot = travelPitch
            }
        }
        on<JumpEvent>(-10) { e ->
            if (!grim.value) return@on
            e.yaw = lockedYaw
            e.cancel()
        }
        on<FallFlyingEvent> { e ->
            if (grim.value) {
                if (!recasting) return@on
                e.value = true
                e.cancel()
                return@on
            }
            val gliding = e.value
            if (recasting && !gliding) {
                e.value = recast()
                e.cancel()
            }
            recasting = gliding
        }
        on<ShouldStopSprintingEvent> { if (canRecast()) it.cancel() }
        on<SetPoseEvent> { e ->
            if (!canRecast() || !grimDurability.value || !grim.value) return@on
            e.cancel()
            e.pose = Pose.STANDING
        }
        on<PlayerUpdateEvent> { tick() }
        on<ReceivePacketEvent> { e -> receive(e) }
        on<SendPacketEvent> { e -> send(e) }
    }

    override fun onEnable() {
        releasing = false
        settle = 0
        mc.player?.let { lockedYaw = it.yRot }
        pongs.clear()
        moves.clear()
    }

    override fun onDisable() {
        val o = mc.options
        if (!PlayerState.physical(o.keyUp)) o.keyUp.isDown = false
        if (!PlayerState.physical(o.keyJump)) o.keyJump.isDown = false
        recasting = false
        releaseHeld()
        handlePings()
        releaseMoves()
        releasePongs()
    }

    private fun tick() {
        val p = mc.player ?: return
        val o = mc.options
        if (changePitch.value || grim.value) RotationManager.rotate(Rotation(if (grim.value) lockedYaw else p.yRot, pitch.value), 201)
        if (settle > 0) {
            settle--
            o.keyJump.isDown = false
            o.keyUp.isDown = false
            o.keySprint.isDown = false
            releaseHeld()
            handlePings()
            releaseMoves()
            releasePongs()
            lockedYaw = p.yRot
            recasting = false
            if (p.isFallFlying) p.stopFallFlying()
            return
        }
        if (!canRecast()) {
            releaseHeld()
            handlePings()
            recasting = false
            primed = false
            return
        }
        p.isSprinting = true
        o.keyJump.isDown = true
        o.keyUp.isDown = true
        o.keySprint.isDown = true
        val fall = if (PlayerState.fallDistance - 1.0 > 0) 0.0f else PlayerState.fallDistance.toFloat()
        if (p.onGround()) primed = false
        if (fall > 0) {
            if (!primed && tunnels()) {
                if (pings.size > 20) {
                    handlePings()
                    p.stopFallFlying()
                    recasting = false
                }
                releaseHeld()
                primed = true
            }
            if (!p.isFallFlying && !recasting && recast()) recasting = true
        }
        (p as LivingEntityBasicAccessor).horizon_setNoJumpDelay(0)
    }

    /** Runs on the network thread. */
    private fun receive(e: ReceivePacketEvent) {
        val p = mc.player ?: return
        when (val packet = e.packet) {
            is ClientboundPlayerPositionPacket -> {
                recasting = false
                settle = 60
                releaseHeld()
                handlePings()
                p.stopFallFlying()
            }
            is ClientboundSetEntityDataPacket -> if (p.isFallFlying && recasting && grimDurability.value) e.cancel()
            is ClientboundPingPacket -> if (p.isFallFlying && recasting && tunnels()) {
                e.cancel()
                pings.add(packet)
            }
        }
    }

    /**
     * A 26.3 server kicks a client for two position packets in one tick; letting several held move
     * packets go at once can do that (only with both tunnels-offset and grim-durability off).
     */
    private fun send(e: SendPacketEvent) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val packet = e.packet
        if (bypass.value == Bypass.FULL && p.isFallFlying && recasting && !releasing && tunnels()) {
            e.cancel()
            held.add(packet)
        }
        if (tunnels() || releasing) return
        val baseY = (p as LocalPlayerBasicAccessor).horizon_yLast()
        val letGo = !descending && frac(baseY) >= 0.4
        if (packet is ServerboundPongPacket) {
            if (recasting && p.isFallFlying) {
                if (!level.noCollision(p.boundingBox.move(0.0, -1.0, 0.0)) &&
                    (if (descending) frac(baseY) < 0.8 else frac(baseY) < 0.4)) {
                    pongs.add(packet)
                    e.cancel()
                }
                if (letGo) releasePongs()
            } else {
                releasePongs()
            }
        }
        if (packet !is ServerboundMovePlayerPacket) return
        if (recasting && p.isFallFlying) {
            val y = packet.getY(lastY)
            if (y - lastY < 0) descending = true else if (y - lastY > 0) descending = false
            lastY = packet.getY(lastY)
            if (!level.noCollision(p.boundingBox.move(0.0, -1.0, 0.0)) &&
                (if (descending) frac(packet.getY(baseY)) < 0.8 else frac(baseY) < 0.4)) {
                moves.add(packet)
                e.cancel()
            }
            if (letGo) releaseMoves()
        } else {
            releaseMoves()
        }
    }

    /** Gliding can be (re)started. */
    private fun canRecast(): Boolean {
        val p = mc.player ?: return false
        if (p.abilities.flying || p.isPassenger || p.isInWater || p.hasEffect(MobEffects.LEVITATION)) return false
        if (!(p.foodData.foodLevel - 6.0f > 0)) return false
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.`is`(Items.ELYTRA)) {
            if (usableElytra(chest)) return true
            if (allowBroken.value && !grim.value) return true
        }
        if (!grimDurability.value) return false
        return Slots.elytra() != -1
    }

    /** Not about to break, and still a glider. */
    fun usableElytra(stack: ItemStack): Boolean {
        if (stack.damageValue >= stack.maxDamage - 1) return false
        return stack.has(DataComponents.GLIDER)
    }

    /**
     * Start gliding (client and server). With grim-durability (and grim) the elytra is only swapped
     * onto the chest around the start packet, and an elytra still worn is swapped for a chestplate afterwards.
     */
    private fun recast(): Boolean {
        if (!canRecast()) return false
        val p = mc.player ?: return false
        val slot = Slots.elytra()
        val swap = grimDurability.value && grim.value
        if (slot != -2 && slot != -1 && swap) {
            Slots.click(slot)
            Slots.click(CHEST_SLOT)
        }
        // Elytra-swap's "ignore the next START_FALL_FLYING" flag is not set here.
        p.startFallFlying()
        Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        if (slot != -2 && slot != -1 && swap) {
            Slots.click(CHEST_SLOT)
            Slots.click(slot, close = true)
        }
        if (slot == -2 && swap) {
            val chestplate = Inventory.find { it.item in chestplates }
            if (chestplate != null) {
                Slots.click(chestplate.slot)
                Slots.click(CHEST_SLOT)
                Slots.click(chestplate.slot, close = true)
            }
        }
        return true
    }

    /** Grim-durability or tunnels-offset. */
    private fun tunnels() = grimDurability.value || tunnelsOffset.value

    private fun frac(v: Double) = v - floor(v)

    private fun releaseHeld() {
        releasing = true
        while (held.isNotEmpty()) Packets.send(held.poll())
        releasing = false
    }

    /** Answer the held pings, on the main thread only. */
    private fun handlePings() {
        if (!mc.isSameThread) return
        val connection = mc.connection ?: return
        while (pings.isNotEmpty()) pings.poll().handle(connection)
    }

    /** Also takes the current yaw as the locked one. */
    private fun releasePongs() {
        releasing = true
        while (pongs.isNotEmpty()) Packets.send(pongs.poll())
        releasing = false
        mc.player?.let { lockedYaw = it.yRot }
    }

    private fun releaseMoves() {
        releasing = true
        while (moves.isNotEmpty()) Packets.send(moves.poll())
        releasing = false
    }

    private companion object {
        /** The chest armour slot of the inventory menu. */
        const val CHEST_SLOT = 6

        val chestplates = setOf(Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE)
    }
}
