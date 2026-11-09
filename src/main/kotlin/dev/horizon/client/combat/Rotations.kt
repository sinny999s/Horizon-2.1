package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.FireworkVectorEvent
import dev.horizon.client.event.JumpEvent
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.LookRenderEvent
import dev.horizon.client.event.MoveRelativeEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostSyncEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.event.TravelEvent
import dev.horizon.client.event.UseItemEvent
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.mixin.LocalPlayerAccessor
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.basic.AntiKnockBack
import dev.horizon.client.module.basic.ElytraRecast
import dev.horizon.client.module.basic.MoveFix
import dev.horizon.client.module.cosmetic.FreeLook
import dev.horizon.client.module.rage.ElytraTarget
import dev.horizon.client.module.rage.MaceElytraTarget
import dev.horizon.client.module.world.FreeCam
import dev.horizon.client.render.World3D
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private val mc get() = Minecraft.getInstance()

/** How a request wants movement corrected (SILENT is read nowhere, so it acts as NONE). */
enum class MoveFixKind { NONE, SILENT, FORCE }

/**
 * A rotation; [restore] sends the real rotation again after an instant request's action. Mutable: the
 * rotation manager snaps the winning request's rotation in place, and Aura reads that snapped value back.
 */
class Rotation(var yaw: Float, var pitch: Float, val restore: Boolean = false, var moveFix: MoveFixKind = MoveFixKind.NONE) {
    fun withMoveFix(k: MoveFixKind): Rotation {
        moveFix = k
        return this
    }

    /** Snaps to the mouse-sensitivity grid around the last sent rotation, in place (double maths). */
    fun gcdFix(): Rotation {
        val g = Math.pow(mc.options.sensitivity().get() * 0.6 + 0.2, 3.0) * 8.0 * 0.15f.toDouble()
        val ly = lastYaw
        val lp = lastPitch
        val dYaw = Mth.wrapDegrees(yaw - ly)
        val dPitch = Mth.wrapDegrees(pitch - lp)
        yaw = ly + ((dYaw / g).roundToInt() * g).toFloat()
        pitch = (lp + ((dPitch / g).roundToInt() * g).toFloat()).coerceIn(-90f, 90f)
        return this
    }

    companion object {
        val lastYaw: Float get() = (mc.player as? LocalPlayerAccessor)?.horizon_yRotLast() ?: 0f
        val lastPitch: Float get() = (mc.player as? LocalPlayerAccessor)?.horizon_xRotLast() ?: 0f

        /** From the eyes, the last sent rotation as the base (so the yaw stays continuous); double maths. */
        fun lookAt(to: Vec3, restore: Boolean = false): Rotation {
            val p = mc.player ?: return Rotation(0f, 0f)
            val dx = to.x - p.x
            val dz = to.z - p.z
            val ly = lastYaw
            val lp = lastPitch
            val yaw = (ly + Mth.wrapDegrees(Math.toDegrees(atan2(dz, dx)) - 90.0 - ly)).toFloat()
            val pitch = (lp + (-Math.toDegrees(atan2(to.y - (p.position().y + p.getEyeHeight(p.pose)), hypot(dx, dz))) - lp)).toFloat()
            return Rotation(yaw, pitch, restore)
        }
    }
}

/** A rotation wanted this tick, and what to do once the server has it (run at PostSync). */
open class RotationRequest(val rotation: Rotation, val priority: Int, var action: () -> Unit)

/** The action runs the moment the request wins (PlayerUpdate −20), before the movement packet. */
class InstantRequest(rotation: Rotation, priority: Int, action: () -> Unit) : RotationRequest(rotation, priority, action)

/** The action runs one tick later (PlayerUpdate +20). */
class GrimRequest(rotation: Rotation, priority: Int, action: () -> Unit) : RotationRequest(rotation, priority, action)

/**
 * The rotation manager: every tick the highest-priority request wins (the first one queued on a tie), its
 * rotation is put on the player for the movement packet only, and restored afterwards. Instant-queue requests send
 * their own rotation packet and act at once. With Move Fix it also makes the player's movement follow that rotation.
 */
object RotationManager {
    private val queue = ArrayList<RotationRequest>()
    private val instant = ArrayList<RotationRequest>()
    private var selected: RotationRequest? = null
    private var deferred: (() -> Unit)? = null

    /** The rotation this tick's movement packet will carry (from the −20 pass until PreSync). */
    private var pending: Rotation? = null
    private var savedYaw = 0f
    private var savedPitch = 0f

    /** The real rotation while travel runs with the server one (never cleared). */
    private var travelSaved: Rotation? = null
    private var useYaw = 0f
    private var usePitch = 0f

    /** The rotation sent with this tick's movement packet, and the previous one. */
    var current: Rotation? = null
        private set
    var previous: Rotation? = null
        private set

    fun queue(r: RotationRequest) {
        Modules.get<AntiKnockBack>().onRotationQueued()
        queue += r
    }

    fun queueInstant(r: RotationRequest) {
        Modules.get<AntiKnockBack>().onRotationQueued()
        instant += r
    }

    /** A rotation with nothing to do afterwards. */
    fun rotate(rot: Rotation, priority: Int = 150) {
        Modules.get<AntiKnockBack>().onRotationQueued()
        queue += RotationRequest(rot, priority) {}
    }

    /** The best queued rotation (snapped in place), else the player's or the last sent one. */
    fun peek(useCurrent: Boolean = false): Rotation {
        var best: RotationRequest? = null
        for (r in queue) if (best == null || r.priority > best.priority) best = r
        if (best != null) return best.rotation.gcdFix()
        val p = mc.player
        return if (useCurrent && p != null) Rotation(p.yRot, p.xRot) else Rotation(Rotation.lastYaw, Rotation.lastPitch)
    }

    fun currentYaw(): Float = pending?.yaw ?: 0f

    private fun freeing() = Modules.get<FreeCam>().enabled || Modules.get<FreeLook>().enabled || Modules.get<ElytraRecast>().enabled

    /** FREE-mode move fix (remap the keys, own velocity maths). */
    private fun silentFix(): Boolean {
        val r = pending ?: return false
        val p = mc.player ?: return false
        val mf = Modules.get<MoveFix>()
        return mf.enabled && r.moveFix != MoveFixKind.FORCE && mf.mode.value != MoveFix.Mode.FOCUS && !p.isHandsBusy && !p.isSwimming &&
            !freeing()
    }

    /** Travel runs with the server rotation. */
    private fun travelFix(): Boolean {
        val r = pending ?: return false
        val p = mc.player ?: return false
        val want = Modules.get<MoveFix>().enabled || Modules.get<ElytraTarget>().enabled || Modules.get<MaceElytraTarget>().enabled ||
            r.moveFix == MoveFixKind.FORCE || p.isHandsBusy || p.isFallFlying || p.isSwimming
        return want && !freeing()
    }

    fun init() {
        EventBus.on<PlayerUpdateEvent>(this, 20) {
            deferred?.invoke()
            deferred = null
        }
        EventBus.on<PlayerUpdateEvent>(this, -20) { resolve() }
        EventBus.on<PreSyncEvent>(this, 20) {
            val p = mc.player ?: return@on
            savedYaw = p.yRot
            savedPitch = p.xRot
            previous = current
            current = null
            val u = pending
            if (u != null) {
                p.yRot = u.yaw
                p.xRot = u.pitch
                current = u
            }
            pending = null
        }
        EventBus.on<PostSyncEvent>(this, -20) {
            val p = mc.player ?: return@on
            selected?.action?.invoke()
            selected = null
            p.yRot = savedYaw
            p.xRot = savedPitch
        }
        EventBus.on<KeyboardInputEvent>(this) { remapInput() }
        EventBus.on<JumpEvent>(this) { e ->
            if (!(travelFix() || silentFix())) return@on
            e.yaw = currentYaw()
            e.cancel()
        }
        EventBus.on<TravelEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (e.pre) {
                if (!travelFix()) return@on
                travelSaved = Rotation(p.yRot, p.xRot)
                val u = pending!!
                p.yRot = u.yaw
                p.xRot = u.pitch
            } else {
                val s = travelSaved
                if (travelFix() && s != null) {
                    p.yRot = s.yaw
                    p.xRot = s.pitch
                }
            }
        }
        EventBus.on<MoveRelativeEvent>(this) { e ->
            if (!silentFix()) return@on
            e.velocity = inputVector(currentYaw(), e.input, e.speed)
            e.cancel()
        }
        EventBus.on<FireworkVectorEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (!(Modules.get<MoveFix>().enabled || Modules.get<ElytraTarget>().enabled || Modules.get<MaceElytraTarget>().enabled)) return@on
            if (!(p.isHandsBusy || p.isFallFlying || p.isSwimming)) return@on
            if (Modules.get<FreeCam>().enabled) return@on
            e.cancel()
            e.vector = sentLook(Rotation.lastPitch, Rotation.lastYaw)
        }
        EventBus.on<UseItemEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (e.pre) {
                useYaw = p.yRot
                usePitch = p.xRot
                val r = peek(true)
                p.yRot = r.yaw
                p.xRot = r.pitch
            } else {
                p.yRot = useYaw
                p.xRot = usePitch
            }
        }
        EventBus.on<LookRenderEvent>(this) { e ->
            val q = current ?: return@on
            val n = previous ?: return@on
            val pt = World3D.partialTick
            e.bodyYaw = Mth.wrapDegrees(Mth.lerp(pt, n.yaw, q.yaw))
            e.yaw = 0f
            e.pitch = Mth.wrapDegrees(Mth.lerp(pt, n.pitch, q.pitch))
        }
        // Client-look: with Aura's setting on, the camera follows the server rotation.
        EventBus.on<Render3DEvent>(this) {
            val q = current ?: return@on
            val n = previous ?: return@on
            val p = mc.player ?: return@on
            val aura = Modules.get<Aura>()
            if (!aura.enabled || !aura.clientLook.value) return@on
            val pt = World3D.partialTick
            p.yRot = Mth.lerp(pt, n.yaw, q.yaw)
            p.xRot = Mth.lerp(pt, n.pitch, q.pitch)
        }
    }

    /** The keys, turned to the server yaw and rounded to 8 directions. */
    private fun remapInput() {
        if (!silentFix()) return
        val p = mc.player ?: return
        val input = p.input
        val mv = input.moveVector
        val f = mv.y
        val s = mv.x
        val a = (p.yRot - pending!!.yaw) * 0.017453292f
        val c = Math.cos(a.toDouble()).toFloat()
        val sn = Math.sin(a.toDouble()).toFloat()
        val f2 = (f * c + s * sn).roundToInt().toFloat()
        val s2 = (s * c - f * sn).roundToInt().toFloat()
        (input as ClientInputAccessor).horizon_setMoveVector(Vec2(s2, f2))
        val old = input.keyPresses
        val sprint = if (Modules.get<Aura>().sprintHook.resetting) false else old.sprint()
        input.keyPresses = Input(f2 > 0, f2 < 0, s2 > 0, s2 < 0, old.jump(), old.shift(), sprint)
    }

    /** Vanilla's input vector, with Math.sin/cos. */
    private fun inputVector(yaw: Float, input: Vec3, speed: Float): Vec3 {
        val d = input.lengthSqr()
        if (d < 1.0E-7) return Vec3.ZERO
        val v = (if (d > 1.0) input.normalize() else input).scale(speed.toDouble())
        val sn = Math.sin((yaw * 0.017453292f).toDouble()).toFloat()
        val c = Math.cos((yaw * 0.017453292f).toDouble()).toFloat()
        return Vec3(v.x * c - v.z * sn, v.y, v.z * c + v.x * sn)
    }

    private fun sentLook(p: Float, y: Float): Vec3 = Vec3(
        (Math.sin((-y * 0.017453292f).toDouble()).toFloat() * Math.cos((p * 0.017453292f).toDouble()).toFloat()).toDouble(),
        (-Math.sin((p * 0.017453292f).toDouble()).toFloat()).toDouble(),
        (Math.cos((-y * 0.017453292f).toDouble()).toFloat() * Math.cos((p * 0.017453292f).toDouble()).toFloat()).toDouble(),
    )

    private fun best(list: List<RotationRequest>, except: RotationRequest? = null): RotationRequest? {
        var w: RotationRequest? = null
        for (r in list) if (r !== except && (w == null || r.priority > w.priority)) w = r
        return w
    }

    private fun resolve() {
        val p = mc.player ?: run {
            queue.clear()
            instant.clear()
            return
        }
        for (r in instant.toList()) {
            Packets.sendRotation(r.rotation.yaw, r.rotation.pitch)
            r.action()
            if (r.rotation.restore) Packets.sendRotation(p.yRot, p.xRot)
        }
        instant.clear()
        val w = best(queue) ?: return
        var u = w.rotation
        if (u.yaw == p.yRot && u.pitch == p.xRot) u = best(queue, w)?.rotation ?: u
        pending = u.gcdFix()
        selected = when (w) {
            is InstantRequest -> {
                w.action()
                null
            }
            is GrimRequest -> {
                deferred = w.action
                null
            }
            else -> w
        }
        queue.clear()
    }
}
