package dev.horizon.client.combat

import dev.horizon.client.event.CollisionEvent
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostTickEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.mixin.ClientLevelAccessor
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.Avoid
import dev.horizon.client.module.misc.FastLatency
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.module.rage.AntiWeb
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.module.rage.Phase
import dev.horizon.client.mixin.KeyMappingAccessor
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

private val mc get() = Minecraft.getInstance()

/** Milliseconds since the last [reset]. */
class MsTimer {
    private var start = System.currentTimeMillis()

    val elapsed: Long get() = System.currentTimeMillis() - start

    fun reset() {
        start = System.currentTimeMillis()
    }

    fun passed(ms: Long) = System.currentTimeMillis() - start >= ms

    /** [passed], resetting when it is. */
    fun passedAndReset(ms: Long): Boolean {
        if (!passed(ms)) return false
        reset()
        return true
    }
}

/** Player ticks since the last [reset]. */
class TickTimer {
    private var start = 0

    fun reset() {
        start = mc.player?.tickCount ?: 0
    }

    /** Count from [offset] ticks after now (negative: as if reset that many ticks ago). */
    fun set(offset: Int) {
        start = (mc.player?.tickCount ?: 0) + offset
    }

    val ticks: Int get() = mc.player?.let { it.tickCount - start } ?: 0

    fun passed(n: Int): Boolean {
        if (ticks < 0) reset()
        return ticks >= n
    }

    /** [passed], resetting when it is. */
    fun passedAndReset(n: Int): Boolean {
        if (!passed(n)) return false
        reset()
        return true
    }
}

/** Packets through the connection (so this client's own send hooks see them). */
object Packets {
    fun send(p: Packet<*>) {
        mc.connection?.send(p)
    }

    /** A packet that carries a block-prediction sequence number, as vanilla's `startPrediction` does. */
    fun sendSequenced(make: (Int) -> Packet<*>) {
        val level = mc.level ?: return
        (level as ClientLevelAccessor).horizon_predictionHandler().startPredicting().use { h -> send(make(h.currentSequence())) }
    }

    /**
     * Tell the server a rotation now, as a position+rotation packet at the current position (a 26.3
     * server kicks for it when the tick's own movement packet also carries a position; kept on purpose).
     */
    fun sendRotation(yaw: Float, pitch: Float) {
        val p = mc.player ?: return
        send(ServerboundMovePlayerPacket.PosRot(p.x, p.y, p.z, yaw, pitch, p.onGround(), p.horizontalCollision))
    }
}

/**
 * The bits the combat modules read: whether a container click was sent without a close, the eye
 * position cached at the start of the player tick, the ping, and the WASD movement offset.
 */
object PlayerState {
    /** A container click was sent and no close since (cleared on every tick without a screen). */
    var containerOpen = false
        private set

    /** `player.getEyePosition()` at PlayerUpdate priority 10. */
    var eye: Vec3 = Vec3.ZERO
        private set

    /** The local player's latency: fast-latency's measurement while it's on, else [tabPing]. */
    val ping: Int
        get() = Modules.get<FastLatency>().let { if (it.enabled) it.latency else tabPing }

    /** The local player's latency from the tab list, refreshed at PlayerUpdate priority 10. */
    var tabPing = 0
        private set

    /** Whether the server thinks we sprint, from our START/STOP_SPRINTING packets. */
    @Volatile
    var serverSprinting = false
        private set

    /** Consecutive ticks gliding with an elytra on. */
    var elytraTicks = 0

    /** Blocks fallen since last on the ground or in a liquid, from the player's own moves. */
    var fallDistance = 0.0
        private set

    /** Set while the main thread is inside `Minecraft.tick` (TickEvent +20 to PostTickEvent −20). */
    private val tickInProgress = AtomicBoolean(false)

    /** On the main thread, or between client ticks (for network-thread handlers). */
    fun safeToAct() = mc.isSameThread || !tickInProgress.get()

    /** A copy of the level's players, taken at PlayerUpdate priority 20. */
    @Volatile
    var players: List<Player> = emptyList()
        private set

    fun init() {
        EventBus.on<SendPacketEvent>(this) { e ->
            when (e.packet) {
                is ServerboundContainerClickPacket -> containerOpen = true
                is ServerboundContainerClosePacket -> containerOpen = false
                is ServerboundPlayerCommandPacket -> when (e.packet.action) {
                    ServerboundPlayerCommandPacket.Action.START_SPRINTING -> serverSprinting = true
                    ServerboundPlayerCommandPacket.Action.STOP_SPRINTING -> serverSprinting = false
                    else -> {}
                }
            }
        }
        EventBus.on<TickEvent>(this, 20) { tickInProgress.set(true) }
        EventBus.on<PostTickEvent>(this, -20) { tickInProgress.set(false) }
        EventBus.on<TickEvent>(this) {
            if (mc.player != null) {
                CollisionEvent.active = Modules.get<Avoid>().enabled || Modules.get<AntiWeb>().enabled ||
                    Modules.get<Phase>().enabled || Modules.get<SpeedMine>().phaseActive()
            }
            if (mc.gui.screen() == null) containerOpen = false
            val p = mc.player ?: return@on
            if (p.isFallFlying && p.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.ELYTRA)) elytraTicks++ else elytraTicks = 0
        }
        EventBus.on<MoveEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (p.onGround() || p.isInLiquid) fallDistance = 0.0 else if (e.movement.y < 0) fallDistance -= e.movement.y
        }
        EventBus.on<PlayerUpdateEvent>(this, 20) { players = ArrayList(mc.level?.players() ?: emptyList()) }
        EventBus.on<PlayerUpdateEvent>(this, 10) {
            val p = mc.player ?: return@on
            eye = p.eyePosition
            tabPing = mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
            ServerInfo.rebuildBlockEntities()
        }
    }

    /** Where the server has us: Blink's held-back position while it's on, else our position. */
    fun serverPos(): Vec3 {
        val p = mc.player ?: return Vec3.ZERO
        val blink = Modules.get<Blink>()
        return (if (blink.enabled) blink.serverPos else null) ?: p.position()
    }

    /** Eye position for placement maths: the cached one for the local player, the live one for others. */
    fun eyeOf(e: net.minecraft.world.entity.Entity): Vec3 = if (e === mc.player) eye else e.eyePosition

    /** Half a block in the direction the movement keys point, as (dx, dz). */
    fun movementOffset(speed: Double = 0.5): DoubleArray {
        val p = mc.player ?: return doubleArrayOf(0.0, 0.0)
        val o = mc.options
        var fwd = if (o.keyUp.isDown) 1.0 else if (o.keyDown.isDown) -1.0 else 0.0
        var str = if (o.keyLeft.isDown) 1.0 else if (o.keyRight.isDown) -1.0 else 0.0
        var yaw = p.yRot
        if (fwd != 0.0) {
            if (str != 0.0) {
                yaw += if (str > 0) (if (fwd > 0) -45 else 45) else (if (fwd > 0) 45 else -45)
                str = 0.0
            }
            fwd = if (fwd > 0) 1.0 else -1.0
        }
        val s = sin(Math.toRadians((yaw + 90).toDouble()))
        val c = cos(Math.toRadians((yaw + 90).toDouble()))
        return doubleArrayOf(fwd * speed * c + str * speed * s, fwd * speed * s - str * speed * c)
    }

    private fun movementKeys() = mc.options.let { listOf(it.keyUp, it.keyDown, it.keyLeft, it.keyRight, it.keyJump, it.keyShift) }

    /** Is the key a mapping is bound to physically held (mouse buttons from the input mixin's set). */
    fun physical(km: KeyMapping): Boolean {
        val k = (km as KeyMappingAccessor).horizon_key()
        if (k.type == InputConstants.Type.MOUSE) return k.value in Keys.heldMouse
        return Keys.down(k.value)
    }

    /** Let go of the movement keys. */
    fun releaseMovementKeys() {
        for (k in movementKeys()) k.isDown = false
    }

    /** Set the movement keys back to what is physically held. */
    fun resyncMovementKeys() {
        for (k in movementKeys()) k.isDown = physical(k)
    }

    /** Tells the server the sneak key changed, and sneaks client-side. */
    fun setSneak(sneak: Boolean) {
        val p = mc.player ?: return
        val i = p.input.keyPresses
        Packets.send(ServerboundPlayerInputPacket(Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), sneak, i.sprint())))
        p.isShiftKeyDown = sneak
    }
}

/** A chat line from a module, "> [Name] text", one legacy-coded string (so the text is gray). */
fun moduleMessage(module: String, text: String) {
    val msg = Component.nullToEmpty("${ChatFormatting.GREEN}> ${ChatFormatting.GRAY}[${ChatFormatting.GOLD}$module${ChatFormatting.GRAY}] $text")
    mc.execute { mc.player?.sendSystemMessage(msg) }
}
