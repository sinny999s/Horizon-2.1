package dev.horizon.client.module.world

import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.CrosshairEvent
import dev.horizon.client.event.FreecamStateEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.SetupTerrainEvent
import dev.horizon.client.mixin.KeyMappingAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.util.Mth
import net.minecraft.util.profiling.Profiler
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera flies on its own, driven by the movement keys (which no longer reach the
 * player), the crosshair picks from the camera, and the server-side rotation follows what the camera looks at. With
 * [freeze] the player doesn't move and sends no movement or input packets.
 */
class FreeCam : Module("free-cam", Categories.WORLD) {
    val hSpeed = float("h-speed", 1.0f, 0.1f..3.0f)
    val vSpeed = float("v-speed", 0.42f, 0.1f..3.0f)
    val freeze = bool("freeze", true)

    // Camera yaw/pitch and their previous-tick values, the camera's eye position and its previous-tick value, the
    // key-driven inputs (forward, strafe, vertical; never reset), and the motion / rotation saved on enable.
    private var yaw = 0f
    private var pitch = 0f
    private var prevYaw = 0f
    private var prevPitch = 0f
    private var pos: Vec3 = Vec3.ZERO
    private var prevPos: Vec3 = Vec3.ZERO
    private var forward = 0f
    private var strafe = 0f
    private var vertical = 0f
    private var savedMotion: Vec3 = Vec3.ZERO
    private var savedRotation = Rotation(0f, 0f)

    init {
        on<PlayerUpdateEvent>(10) {
            val p = mc.player
            if (mc.level == null || p == null) {
                disable()
                return@on
            }
            val s = mc.gui.screen()
            if (s is LevelLoadingScreen || s is ServerReconfigScreen) disable()
            prevYaw = yaw
            prevPitch = pitch
            yaw = p.yRot
            pitch = p.xRot
            val off = offset(hSpeed.value, forward, strafe, p.yRot)
            prevPos = pos
            pos = pos.add(off[0], vertical.toDouble(), off[1])
            val hit = mc.hitResult ?: return@on
            if (!moving()) RotationManager.rotate(Rotation.lookAt(hit.location), 0)
        }
        on<SetupTerrainEvent> { e -> if (mc.level != null && mc.player != null) e.cancel() else disable() }
        on<FreecamStateEvent> { e ->
            if (mc.level == null || mc.player == null) {
                disable()
                return@on
            }
            e.cancel()
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            e.x = Mth.lerp(pt.toDouble(), prevPos.x, pos.x)
            e.y = Mth.lerp(pt.toDouble(), prevPos.y, pos.y)
            e.z = Mth.lerp(pt.toDouble(), prevPos.z, pos.z)
            e.yaw = Mth.lerp(pt, prevYaw, yaw)
            e.pitch = Mth.lerp(pt, prevPitch, pitch)
        }
        on<CrosshairEvent> { e ->
            if (mc.level == null || mc.player == null) {
                disable()
                return@on
            }
            Profiler.get().pop()
            e.cancel()
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            mc.hitResult = pick(Mth.lerp(pt, prevYaw, yaw), Mth.lerp(pt, prevPitch, pitch), Mth.lerp(pt.toDouble(), prevPos.x, pos.x),
                Mth.lerp(pt.toDouble(), prevPos.y, pos.y), Mth.lerp(pt.toDouble(), prevPos.z, pos.z))
        }
        on<InputEvent> { e -> input(e) }
        // Cancels the movement-input event; its hook sits after the last write of KeyboardInput.tick, so this changes
        // nothing (on purpose).
        on<KeyboardInputEvent> { e -> e.cancel() }
        on<MoveEvent>(-10) { e ->
            if (mc.level == null || mc.player == null) {
                disable()
                return@on
            }
            if (freeze.value) {
                e.x = 0.0
                e.y = 0.0
                e.z = 0.0
                e.cancel()
            }
        }
        on<SendPacketEvent> { e ->
            if (mc.player == null || mc.level == null || !freeze.value) return@on
            if (e.packet is ServerboundMovePlayerPacket || e.packet is ServerboundPlayerInputPacket) e.cancel()
        }
    }

    override fun onEnable() {
        val p = mc.player
        if (mc.level == null || p == null) return
        mc.smartCull = false
        pitch = p.xRot
        yaw = p.yRot
        prevPitch = pitch
        prevYaw = yaw
        pos = Vec3(p.x, p.y + p.getEyeHeight(p.pose), p.z)
        prevPos = Vec3(p.x, p.y, p.z)
        savedMotion = p.deltaMovement
        savedRotation = Rotation(p.yRot, p.xRot)
    }

    override fun onDisable() {
        val p = mc.player
        if (p == null || mc.level == null) return
        mc.smartCull = true
        if (!freeze.value) return
        p.deltaMovement = savedMotion
        p.forceSetRotation(savedRotation.yaw, false, savedRotation.pitch, false)
    }

    /**
     * The movement keys drive the camera (Press / Hold set the input, Release clears it) and are let go of; a movement
     * key never reaches vanilla. The device must match too, as 26.3's key and button codes overlap.
     */
    private fun input(e: InputEvent) {
        if (mc.level == null || mc.player == null) {
            disable()
            return
        }
        if (e.key == -1) return
        val o = mc.options
        val up = bound(o.keyUp, e)
        val down = bound(o.keyDown, e)
        val left = bound(o.keyLeft, e)
        val right = bound(o.keyRight, e)
        val jump = bound(o.keyJump, e)
        val shift = bound(o.keyShift, e)
        if (e.action == InputEvent.Action.Press || e.action == InputEvent.Action.Hold) {
            if (up) forward = 1.0f
            if (down) forward = -1.0f
            if (left) strafe = 1.0f
            if (right) strafe = -1.0f
            if (jump) vertical = vSpeed.value
            if (shift) vertical = -vSpeed.value
        }
        if (e.action == InputEvent.Action.Release) {
            if (up) forward = 0.0f
            if (down) forward = 0.0f
            if (left) strafe = 0.0f
            if (right) strafe = 0.0f
            if (jump) vertical = 0.0f
            if (shift) vertical = 0.0f
        }
        PlayerState.releaseMovementKeys()
        if (up || down || left || right || jump || shift) e.cancel()
    }

    private fun bound(km: KeyMapping, e: InputEvent): Boolean {
        val k = (km as KeyMappingAccessor).horizon_key()
        val type = if (e.device == InputEvent.Device.Mouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYBOARD
        return k.type == type && k.value == e.key
    }

    /** The player's movement input is non-zero. */
    private fun moving(): Boolean {
        val mv = mc.player?.input?.moveVector ?: return false
        return mv.y != 0.0f || mv.x != 0.0f
    }

    /** The (x, z) step for [fwd] / [str] at [speed] along [yaw0]. */
    private fun offset(speed: Float, fwd0: Float, str0: Float, yaw0: Float): DoubleArray {
        var fwd = fwd0
        var str = str0
        var yaw = yaw0
        if (fwd != 0.0f) {
            if (str != 0.0f) {
                yaw += if (str > 0.0f) (if (fwd > 0.0f) -45.0f else 45.0f) else (if (fwd > 0.0f) 45.0f else -45.0f)
                str = 0.0f
            }
            fwd = if (fwd > 0.0f) 1.0f else -1.0f
        }
        val s = sin(Math.toRadians((yaw + 90.0f).toDouble()))
        val c = cos(Math.toRadians((yaw + 90.0f).toDouble()))
        return doubleArrayOf((fwd * speed) * c + (str * speed) * s, (fwd * speed) * s - (str * speed) * c)
    }

    /** The look vector, in float. */
    private fun direction(pitch: Float, yaw: Float): Vec3 {
        val r = (Math.PI / 180.0).toFloat()
        return Vec3(
            (sin((-yaw * r).toDouble()).toFloat() * cos((pitch * r).toDouble()).toFloat()).toDouble(),
            (-sin((pitch * r).toDouble()).toFloat()).toDouble(),
            (cos((-yaw * r).toDouble()).toFloat() * cos((pitch * r).toDouble()).toFloat()).toDouble(),
        )
    }

    /**
     * A 5-block crosshair pick from the camera. The block ray starts at the camera position (in
     * float); the entity ray starts one eye height above it (on purpose).
     */
    private fun pick(yaw: Float, pitch: Float, x: Double, y: Double, z: Double): HitResult {
        val p = mc.player!!
        val level = mc.level!!
        val start = Vec3(x.toFloat().toDouble(), y.toFloat().toDouble(), z.toFloat().toDouble())
        val look = direction(pitch, yaw)
        val block = level.clip(ClipContext(start, start.add(look.x * 5.0f, look.y * 5.0f, look.z * 5.0f), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, p))
        val eye = Vec3(x, y, z).add(0.0, p.getEyeHeight(p.pose).toDouble(), 0.0)
        val maxSq = if (block.type == HitResult.Type.MISS) 25.0 else block.location.distanceToSqr(eye)
        val dir = direction(pitch, yaw)
        val end = eye.add(dir.x * 5, dir.y * 5, dir.z * 5)
        val box = AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3).expandTowards(dir.scale(5.0)).inflate(1.0, 1.0, 1.0)
        val entity = ProjectileUtil.getEntityHitResult(p, eye, end, box, { !it.isSpectator && it.isPickable }, maxSq)
        if (entity != null) {
            if (eye.distanceToSqr(entity.location) >= maxSq && block.type != HitResult.Type.MISS) return block
            if (entity.entity is LivingEntity) return entity
        }
        return block
    }
}
