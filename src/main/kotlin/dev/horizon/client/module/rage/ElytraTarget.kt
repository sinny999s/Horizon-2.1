package dev.horizon.client.module.rage

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Motion
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FireworkVectorEvent
import dev.horizon.client.event.FireworkVelocityEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostSyncEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.equip.ElytraSwap
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Theme
import dev.horizon.client.render.World3D
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Fireworks while gliding slower than `min-speed`, a per-pitch firework boost, a
 * look at open air after each aura hit (peek-assist, with blink between peeks for fake-lag), and the aura's prediction
 * of gliding targets ([resolve]).
 */
class ElytraTarget : Module("elytra-target", Categories.RAGE) {
    val predictTicks = float("predict-ticks", 0.0f, 0.0f..5.0f)
    val alwaysPredict = bool("always-predict", false)
    val delay = int("delay", 10, 0..30)
    val offhandFirework = bool("offhand-firework", true)
    val bravoHvhSwap = bool("bravo-hvh-swap", true) { offhandFirework.value }
    val minSpeed = int("min-speed", 120, 0..200)
    val pauseWhileEating = bool("pause-while-eating", true)
    val peekAssist = bool("peek-assist", true)
    val fakeLag = bool("fake-lag", true) { peekAssist.value }
    val onlyGroundPeek = bool("only-ground-peek", true) { peekAssist.value }
    val boost = bool("boost", true)
    val customSpeed = bool("custom-speed", false) { boost.value }
    val n0Degrees = float("0-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n5Degrees = float("5-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n10Degrees = float("10-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n15Degrees = float("15-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n20Degrees = float("20-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n25Degrees = float("25-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n30Degrees = float("30-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n35Degrees = float("35-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n40Degrees = float("40-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n45Degrees = float("45-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n50Degrees = float("50-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n55Degrees = float("55-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n60Degrees = float("60-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n65Degrees = float("65-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n70Degrees = float("70-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n75Degrees = float("75-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n80Degrees = float("80-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n85Degrees = float("85-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }
    val n90Degrees = float("90-degrees", 0.15f, 0.0f..1.0f) { boost.value && customSpeed.value }

    /** Peeking; PlayerUpdate asks for [peekRotation] meanwhile. */
    @Volatile
    private var peeking = false

    /** Never cleared. */
    private var peekRotation: Rotation? = null

    /** The last peek direction, skipped by the next peek. */
    private var lastPeekDirection: Direction? = null

    /** Fake-lag turned blink on. */
    @Volatile
    private var blinkByUs = false

    /** The aura target's box (current / previous), written by [resolve]. */
    private var renderBox: AABB? = null
    private var prevRenderBox: AABB? = null

    /** Prediction anchor, shared by all targets, never cleared. */
    private var predictAnchor: Vec3? = null

    private val fireworkTimer = TickTimer()
    private val redeployTimer = TickTimer()

    /** Reset when the aura target swings. */
    private val targetSwing = TickTimer()

    private val aura get() = Modules.get<Aura>()
    private val blink get() = Modules.get<Blink>()

    init {
        on<PostSyncEvent> {
            val p = mc.player ?: return@on
            if (fakeLag.value) blink.forceLagSettings()
            val t = aura.target
            val peekCond = p.inventory.getItem(38).item == Items.ELYTRA && p.isFallFlying && t is Player &&
                (!onlyGroundPeek.value || !t.isFallFlying)
            if (!peekCond && blink.enabled) {
                Notifications.silent = true
                blink.disable()
                blinkByUs = false
                Notifications.silent = false
            }
            if (aura.timer.countdown == aura.timer.scheduled && peekAssist.value && !Modules.get<ElytraFreeze>().enabled && peekCond) {
                peeking = true
                peekRotation = choosePeekRotation()
                PEEK_EXECUTOR.execute { peekEnd() }
            }
            if (redeployTimer.passed(5) && !p.onGround()) {
                if (Inventory.find(Items.FIREWORK_ROCKET) != null && p.tryToStartFallFlying()) {
                    Modules.get<ElytraSwap>().skipFirework = true
                    Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                    redeployTimer.reset()
                }
            }
        }

        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            val rot = peekRotation
            if (peeking && rot != null) RotationManager.rotate(rot, 200)
            if (pauseWhileEating.value && p.isUsingItem &&
                (p.mainHandItem.has(DataComponents.FOOD) || p.mainHandItem.has(DataComponents.POTION_CONTENTS))) return@on
            if (p.isFallFlying && fireworkTimer.passed(delay.value) && Motion.speed3D * 72.0f < minSpeed.value.toFloat()) {
                useFirework(offhandFirework.value, bravoHvhSwap.value)
                fireworkTimer.reset()
            }
        }

        on<FireworkVelocityEvent> { e ->
            val p = mc.player ?: return@on
            e.cancel()
            val look0 = p.lookAngle
            val ve = EventBus.post(FireworkVectorEvent(look0))
            val look = if (ve.cancelled) ve.vector else look0
            val pitch = abs(Math.toDegrees(-atan2(look.y, hypot(look.x, look.z))).toFloat())
            val speed: Float = if (!blink.enabled && boost.value) {
                val table = if (customSpeed.value) customTable() else BOOST
                TABLE_ORDER.minByOrNull { abs(pitch - it) }?.let { table[it] } ?: 0.1f
            } else 0.1f
            val m = p.deltaMovement
            e.velocity = m.add(look.x * speed + (look.x * 1.5f - m.x) * 0.5, look.y * speed + (look.y * 1.5f - m.y) * 0.5,
                look.z * speed + (look.z * 1.5f - m.z) * 0.5)
        }

        on<Render3DEvent> { e ->
            val t = aura.target
            val prev = prevRenderBox ?: return@on
            val cur = renderBox ?: return@on
            if (t !is Player || !t.isFallFlying || predictTicks.value == 0f) return@on
            val pt = e.partialTick.toDouble()
            val box = AABB(prev.minX + (cur.minX - prev.minX) * pt, prev.minY + (cur.minY - prev.minY) * pt, prev.minZ + (cur.minZ - prev.minZ) * pt,
                prev.maxX + (cur.maxX - prev.maxX) * pt, prev.maxY + (cur.maxY - prev.maxY) * pt, prev.maxZ + (cur.maxZ - prev.maxZ) * pt)
            World3D.fill(box, Theme.gradient(0))
            World3D.outline(box, Colors.withAlpha(Theme.gradient(0), 1f))
        }

        // 26.3 sends swings as their own packet, not as ClientboundAnimatePacket actions 0 / 3.
        on<ReceivePacketEvent> { e ->
            val pk = e.packet
            val t = aura.target ?: return@on
            if (pk is ClientboundSwingAnimationPacket && pk.entityId() == t.id) targetSwing.reset()
        }
    }

    override fun onDisable() {
        if (blinkByUs && blink.enabled) {
            Notifications.silent = true
            blink.disable()
            Notifications.silent = false
        }
    }

    /** 325 ms after a peek starts (on its own thread), stop peeking and restart fake-lag's blink. */
    private fun peekEnd() {
        Thread.sleep(325)
        peeking = false
        Notifications.silent = true
        blink.disable()
        blinkByUs = false
        if (fakeLag.value) {
            blink.enable()
            blinkByUs = true
        }
        Notifications.silent = false
    }

    /** How many ticks ahead to put a gliding aura target (0 right after it stopped moving). */
    fun predictFactor(): Float {
        val t = aura.target ?: return 0f
        val tp = t.position()
        val anchor = predictAnchor
        if (anchor == null) {
            predictAnchor = tp
            return if (targetSwing.passed(15) || alwaysPredict.value) predictTicks.value else 0f
        }
        if (anchor.distanceTo(tp) - 0.1f < 0.0) {
            predictAnchor = tp
            return 0f
        }
        return if (targetSwing.passed(15) || alwaysPredict.value) predictTicks.value else 0f
    }

    fun setRenderBox(box: AABB) {
        prevRenderBox = renderBox
        renderBox = box
    }

    /**
     * The aura's resolver: every remote player that moved is put back to its own position after
     * [action]; meanwhile a gliding one is moved ahead by [predictFactor] while elytra-target is on.
     */
    fun resolve(action: () -> Unit) {
        val level = mc.level ?: return action()
        val remotes = level.players().filterIsInstance<RemotePlayer>()
        val saved = HashMap<RemotePlayer, Vec3>()
        for (p in remotes) {
            if (p.x == p.xo && p.y == p.yo && p.z == p.zo) {
                if (aura.target === p) setRenderBox(p.boundingBox)
                continue
            }
            saved[p] = p.position()
            if (!p.isFallFlying || !enabled) continue
            if (predictFactor() <= 0f) continue
            val f = predictFactor()
            val nx = p.x + (p.x - p.xo) * f
            val ny = p.y + (p.y - p.yo) * f
            val nz = p.z + (p.z - p.zo) * f
            p.setPos(nx, ny, nz)
            if (aura.target === p) setRenderBox(AABB(nx - 0.3, ny + 0.0, nz - 0.3, nx + 0.3, ny + 0.6, nz + 0.3))
        }
        action()
        for (p in remotes) saved[p]?.let { if (it != Vec3.ZERO) p.setPos(it) }
    }

    /** The first face direction (not the last one) with 14 blocks of air at feet and head height. */
    private fun choosePeekRotation(): Rotation {
        val p = mc.player ?: return Rotation(Rotation.lastYaw, -88.0f)
        val level = mc.level ?: return Rotation(Rotation.lastYaw, -88.0f)
        for (dir in Direction.entries) {
            val rot = faceRotation(dir)
            if (dir == lastPeekDirection) continue
            val sx = (dir.stepX * 14.0f).toDouble()
            val sy = (dir.stepY * 14.0f).toDouble()
            val sz = (dir.stepZ * 14.0f).toDouble()
            val feet = p.position()
            val h = p.boundingBox.ysize
            val r1 = level.clip(ClipContext(feet, feet.add(sx, sy, sz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
            val r2 = level.clip(ClipContext(feet.add(0.0, h, 0.0), feet.add(sx, sy + h, sz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
            if (r1.type == HitResult.Type.MISS && r2.type == HitResult.Type.MISS) {
                lastPeekDirection = dir
                peekRotation = rot
                return rot
            }
        }
        return Rotation(Rotation.lastYaw, -88.0f)
    }

    /** Facing [dir], the yaw kept in the last sent yaw's turn. */
    private fun faceRotation(dir: Direction): Rotation {
        val yaw = when (dir) {
            Direction.NORTH -> 180.0f
            Direction.SOUTH -> 0.0f
            Direction.WEST -> 90.0f
            Direction.EAST -> -90.0f
            else -> 0.0f
        }
        val pitch = when (dir) {
            Direction.DOWN -> 88.0f
            Direction.UP -> -88.0f
            else -> -10.0f
        }
        val rev = (Rotation.lastYaw / 360.0f).toInt()
        return Rotation(yaw + rev * 360, pitch)
    }

    /** One firework, from the off-hand or the hotbar (also used by other elytra modules). */
    fun useFirework(offhand: Boolean, altSwap: Boolean) {
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        val hot = Inventory.hotbarFind(Items.FIREWORK_ROCKET)
        val inv = Inventory.find(Items.FIREWORK_ROCKET)
        fun hand() = if (p.offhandItem.item == Items.FIREWORK_ROCKET) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND

        if (offhand && !blink.enabled) {
            if (p.offhandItem.item == Items.FIREWORK_ROCKET) {
                gm.useItem(p, hand())
                return
            }
            if (inv == null) {
                noFireworks()
                return
            }
            val guiMove = Modules.get<GuiMove>()
            guiMove.internalClick = true
            if (altSwap) {
                Inventory.click(inv.slot, 0, ContainerInput.PICKUP, false)
                Inventory.click(45, 0, ContainerInput.PICKUP, false)
                Inventory.click(inv.slot, 0, ContainerInput.PICKUP, false)
            } else {
                Inventory.swap(inv.slot, 40, false)
            }
            gm.useItem(p, hand())
            Inventory.swap(inv.slot, 40, true)
            guiMove.internalClick = false
            return
        }

        Inventory.saveAndSelect(Inventory.selected) // remember the slot
        if (hot != null) {
            Inventory.selectHotbar(hot.slot)
        } else if (inv != null) {
            val b = p.inventory.selectedSlot % 8 + 1
            Inventory.swap(inv.slot, b, true)
            Inventory.selectHotbar(b)
        } else if (p.offhandItem.item != Items.FIREWORK_ROCKET) {
            noFireworks()
            return
        }
        gm.useItem(p, hand())
        Inventory.restore()
        // The restored slot, not b (on purpose).
        if (hot == null && inv != null) Inventory.swap(inv.slot, p.inventory.selectedSlot, true)
    }

    /** Turn off with a chat line. */
    private fun noFireworks() {
        disable()
        moduleMessage(name.trim(), "You've got no fireworks!")
    }

    private fun customTable(): Map<Float, Float> = mapOf(
        0f to n0Degrees.value, 5f to n5Degrees.value, 10f to n10Degrees.value, 15f to n15Degrees.value, 20f to n20Degrees.value,
        25f to n25Degrees.value, 30f to n30Degrees.value, 35f to n35Degrees.value, 40f to n40Degrees.value, 45f to n45Degrees.value,
        50f to n50Degrees.value, 55f to n55Degrees.value, 60f to n60Degrees.value, 65f to n65Degrees.value, 70f to n70Degrees.value,
        75f to n75Degrees.value, 80f to n80Degrees.value, 85f to n85Degrees.value, 90f to n90Degrees.value)

    private companion object {
        val PEEK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()

        /** Firework strength by pitch (degrees from level). */
        val BOOST: Map<Float, Float> = mapOf(
            0f to 0.155f, 5f to 0.16f, 10f to 0.19f, 15f to 0.22f, 20f to 0.22f, 25f to 0.27f, 30f to 0.28f, 35f to 0.34f,
            40f to 0.37f, 45f to 0.37f, 50f to 0.34f, 55f to 0.34f, 60f to 0.2f, 65f to 0.18f, 70f to 0.16f, 75f to 0.16f,
            80f to 0.13f, 85f to 0.13f, 90f to 0.13f)

        /** The keys in HashMap iteration order (on purpose): exact ties go to the first. */
        val TABLE_ORDER = listOf(0f, 5f, 10f, 20f, 40f, 80f, 65f, 25f, 50f, 85f, 35f, 70f, 15f, 30f, 60f, 45f, 90f, 75f, 55f)
    }
}
