package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.basic.GuiMove
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import net.minecraft.world.effect.MobEffects
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.TickEvent

private val mc get() = Minecraft.getInstance()

/**
 * The client's tick-rate factor. Reset to 1 at the start of every player tick
 * (PlayerUpdate +20); a module that wants a factor sets it again every tick, the last write wins. The
 * `DeltaTracker.Timer` mixin applies it to the frame's ms-to-ticks step while it isn't 1 (with the raw 50 ms base).
 */
object TimerManager {
    @JvmStatic
    var factor = 1.0f

    fun init() {
        EventBus.on<PlayerUpdateEvent>(this, 20) { factor = 1.0f }
    }
}

/** Helpers the movement modules read (last tick's horizontal speed is ServerInfo's). */
object Movement {
    /** A movement key is pushing the input (the move vector isn't zero). */
    fun moving(): Boolean {
        val p = mc.player ?: return false
        if (mc.level == null) return false
        val mv = p.input.moveVector
        return mv.y != 0f || mv.x != 0f
    }

    /**
     * [speed] in the direction [forward]/[strafe] point from [yaw], as (dx, dz);
     * by default the input's move vector and the player's yaw.
     */
    fun strafe(speed: Float, forward: Float? = null, strafe: Float? = null, yaw: Float? = null): DoubleArray {
        val p = mc.player!!
        var f = forward ?: p.input.moveVector.y
        var s = strafe ?: p.input.moveVector.x
        var y = yaw ?: p.yRot
        if (f != 0f) {
            if (s != 0f) {
                y += if (s > 0f) (if (f > 0f) -45f else 45f) else (if (f > 0f) 45f else -45f)
                s = 0f
            }
            f = if (f > 0f) 1f else -1f
        }
        val sn = sin(Math.toRadians((y + 90f).toDouble()))
        val c = cos(Math.toRadians((y + 90f).toDouble()))
        return doubleArrayOf(f * speed * c + s * speed * sn, f * speed * sn - s * speed * c)
    }

    /** [p]'s box touches a cobweb (blocks within 2 around, 1 below to 4 above its block). */
    fun inCobweb(p: Player): Boolean {
        val level = mc.level ?: return false
        val c = BlockPos.containing(p.position())
        for (x in c.x - 2..c.x + 2) for (y in c.y - 1..c.y + 4) for (z in c.z - 2..c.z + 2) {
            val pos = BlockPos(x, y, z)
            if (p.boundingBox.intersects(AABB(pos)) && level.getBlockState(pos).block == Blocks.COBWEB) return true
        }
        return false
    }

    /** [speed] straight along the player's yaw, as (dx, dz). */
    fun forward(speed: Double): DoubleArray {
        val yaw = mc.player!!.yRot
        return doubleArrayOf(speed * cos(Math.toRadians((yaw + 90f).toDouble())), speed * sin(Math.toRadians((yaw + 90f).toDouble())))
    }

    /** The player's horizontal distance moved last tick, taken at Tick. */
    var lastHorizontal = 0f
        private set

    private var plugged = false

    /** Plugs the always-on tracking; modules reading it call this when built. */
    fun init() {
        if (plugged) return
        plugged = true
        EventBus.on<TickEvent>(this) {
            val p = mc.player ?: return@on
            lastHorizontal = hypot(p.x - p.xo, p.z - p.zo).toFloat()
        }
    }

    /**
     * [speed] blocks along [yaw] for the given input (a diagonal turns the yaw by 45° and moves
     * straight), as (x, z).
     */
    fun direction(speed: Float, forwardIn: Float, strafeIn: Float, yawIn: Float): DoubleArray {
        var forward = forwardIn
        var strafe = strafeIn
        var yaw = yawIn
        if (forward != 0.0f) {
            if (strafe != 0.0f) {
                yaw += if (strafe > 0.0f) (if (forward > 0.0f) -45.0f else 45.0f) else (if (forward > 0.0f) 45.0f else -45.0f)
                strafe = 0.0f
            }
            forward = if (forward > 0.0f) 1.0f else -1.0f
        }
        val s = sin(Math.toRadians((yaw + 90.0f).toDouble()))
        val c = cos(Math.toRadians((yaw + 90.0f).toDouble()))
        return doubleArrayOf(forward * speed * c + strafe * speed * s, forward * speed * s - strafe * speed * c)
    }

    /**
     * The move's x/z set to [speed] blocks in the direction of the movement input (a
     * diagonal turns the yaw by 45°), or zero with no input.
     */
    fun strafe(e: MoveEvent, speed: Double) {
        val p = mc.player ?: return
        var forward = p.input.moveVector.y.toDouble()
        var strafe = p.input.moveVector.x.toDouble()
        var yaw = p.yRot
        if (forward == 0.0 && strafe == 0.0) {
            e.x = 0.0
            e.z = 0.0
            return
        }
        if (forward != 0.0) {
            if (strafe > 0.0) yaw += if (forward > 0.0) -45 else 45
            else if (strafe < 0.0) yaw += if (forward > 0.0) 45 else -45
            strafe = 0.0
            forward = if (forward > 0.0) 1.0 else -1.0
        }
        val s = sin(Math.toRadians((yaw + 90.0f).toDouble()))
        val c = cos(Math.toRadians((yaw + 90.0f).toDouble()))
        e.x = forward * speed * c + strafe * speed * s
        e.z = forward * speed * s - strafe * speed * c
    }

    /** The NCP base speed 0.2873, scaled up by Speed and down by Jump Boost and Slowness. */
    fun baseSpeed(): Double {
        val p = mc.player ?: return 0.0
        var v = 0.2873
        p.getEffect(MobEffects.SPEED)?.let { v *= 1.0 + 0.2 * (it.amplifier + 1) }
        p.getEffect(MobEffects.JUMP_BOOST)?.let { v /= 1.0 + 0.2 * (it.amplifier + 1) }
        p.getEffect(MobEffects.SLOWNESS)?.let { v /= 1.0 + 0.2 * (it.amplifier + 1) }
        return v
    }

    /** The jump's vertical speed, 0.39999995 plus 0.1 per Jump Boost level. */
    fun jumpMotion(): Double {
        val p = mc.player ?: return 0.0
        var v = 0.39999995f.toDouble()
        p.getEffect(MobEffects.JUMP_BOOST)?.let { v += (it.amplifier.toDouble() + 1.0) * 0.1 }
        return v
    }

    /** The yaw the held movement keys point in (the key mappings, not the input). */
    fun keysYaw(): Float {
        val o = mc.options
        val forward = if (o.keyUp.isDown) 1.0 else if (o.keyDown.isDown) -1.0 else 0.0
        val strafe = if (o.keyLeft.isDown) 1.0 else if (o.keyRight.isDown) -1.0 else 0.0
        var yaw = mc.player!!.yRot
        if (forward == 0.0 && strafe == 0.0) return yaw
        if (forward != 0.0) {
            if (strafe > 0) yaw += if (forward > 0) -45.0f else -135.0f
            else if (strafe < 0) yaw += if (forward > 0) 45.0f else 135.0f
            else if (forward < 0) yaw += 180.0f
            return yaw
        }
        return if (strafe > 0) yaw - 90.0f else yaw + 90.0f
    }
}

/** Find a stack the way a [Switch] mode looks (hotbar, whole inventory, or hotbar first). */
fun switchFind(mode: Switch, pred: (ItemStack) -> Boolean): Found? = when (mode) {
    Switch.NORMAL, Switch.SILENT -> Inventory.hotbarFind(pred)
    Switch.INVENTORY_NORMAL, Switch.INVENTORY_SILENT -> Inventory.find(pred)
    Switch.NORMAL_FULL, Switch.SILENT_FULL -> Inventory.hotbarFind(pred) ?: Inventory.find(pred)
}

/**
 * Switch to [slot] (a hotbar index, or a container slot id for the inventory
 * modes), run [action], and switch back for the silent modes. (Also raises gui-move's pause flag meanwhile.)
 */
fun switchAnd(slot: Int, mode: Switch, action: () -> Unit) {
    val p = mc.player ?: return
    val guiMove = Modules.get<GuiMove>()
    guiMove.internalClick = true
    when (mode) {
        Switch.NORMAL -> {
            if (p.inventory.selectedSlot != slot) Inventory.selectHotbar(slot)
            action()
        }
        Switch.SILENT -> {
            val prev = p.inventory.selectedSlot
            if (Inventory.serverSlot != slot) Inventory.selectHotbar(slot)
            action()
            if (Inventory.serverSlot != prev) Inventory.selectHotbar(prev)
        }
        Switch.INVENTORY_NORMAL -> {
            invSwap(p, slot)
            action()
        }
        Switch.INVENTORY_SILENT -> {
            invSwap(p, slot)
            action()
            invSwap(p, slot)
        }
        Switch.NORMAL_FULL -> switchAnd(slot, if (slot >= 9) Switch.INVENTORY_NORMAL else Switch.NORMAL, action)
        Switch.SILENT_FULL -> switchAnd(slot, if (slot >= 9) Switch.INVENTORY_SILENT else Switch.SILENT, action)
    }
    guiMove.internalClick = false
}

private fun invSwap(p: Player, slot: Int) {
    mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, p.inventory.selectedSlot, ContainerInput.SWAP, p)
}
