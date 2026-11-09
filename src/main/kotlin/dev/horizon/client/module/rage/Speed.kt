package dev.horizon.client.module.rage

import dev.horizon.client.combat.ElytraSwapHook
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.combat.containerClick
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.attack.Aura
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.vehicle.boat.Boat
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.math.max
import kotlin.math.min

/**
 * NCP / Strict Strafe: the NCP bunny hop (a jump, the 2.149 boost, the 0.66 decay towards the
 * base speed, then friction), strafed along the input. Matrix jumps off the ground near blocks and, while falling, runs
 * the timer at 1.088. Old Matrix doubles the move just above a block. Grim Entity pushes the player by [speed] % per
 * entity (or rolled-back player box) within [distance]. Firework puts on an elytra, starts gliding and fires a rocket
 * every [delay] ticks.
 */
class Speed : Module("speed", Categories.RAGE) {
    enum class Mode { STRICT_STRAFE, MATRIX, NCP, GRIM_ENTITY, FIREWORK, OLD_MATRIX }
    enum class BoostAngle { CLIENT, SERVER, PREDICT }

    val mode = enum("mode", Mode.NCP)
    val useTimer = bool("use-timer", false) { (mode.value == Mode.STRICT_STRAFE || mode.value == Mode.MATRIX || mode.value == Mode.NCP) }
    val pauseInLiquids = bool("pause-in-liquids", false)
    val pauseWhileSneaking = bool("pause-while-sneaking", false)
    val fireSlot = int("fire-slot", 1, 1..9) { mode.value == Mode.FIREWORK }
    val delay = int("delay", 8, 1..20) { mode.value == Mode.FIREWORK }
    val armorStands = bool("armor-stands", false) { mode.value == Mode.GRIM_ENTITY }
    val distance = float("distance", 1.0f, 0.1f..2.0f) { mode.value == Mode.GRIM_ENTITY }
    val speed = float("speed", 8.0f, 0.1f..10.0f) { mode.value == Mode.GRIM_ENTITY }
    val groundSpeed = float("ground-speed", 8.0f, 0.1f..10.0f) { mode.value == Mode.GRIM_ENTITY }
    val boostAngle = enum("boost-angle", BoostAngle.SERVER) { mode.value == Mode.GRIM_ENTITY }
    val predictTicks = float("predict-ticks", 3.0f, 0.0f..10.0f) { mode.value == Mode.GRIM_ENTITY && boostAngle.value == BoostAngle.PREDICT }
    val rollbackTicks = int("rollback-ticks", 5, 0..30) { mode.value == Mode.GRIM_ENTITY }

    // Hop speed, hop stage, NCP tick counter and firework cooldown (shared).
    private var hopSpeed = 0.2873
    private var stage = 1
    private var counter = 0
    private var lastForward = 0f

    /** Each player's last [rollbackTicks] boxes by entity id (Grim Entity counts those too). */
    private val rollback = LinkedHashMap<Int, ArrayDeque<AABB>>()

    override fun onEnable() {
        stage = 1
        counter = 0
        hopSpeed = 0.2873
    }

    private fun paused(): Boolean {
        val p = mc.player ?: return true
        if (p.isInLiquid && pauseInLiquids.value) return true
        return p.isShiftKeyDown && pauseWhileSneaking.value
    }

    private fun recordRollback() {
        val level = mc.level ?: return
        for (pl in level.players()) {
            val boxes = rollback.getOrPut(pl.id) { ArrayDeque() }
            boxes.addLast(pl.boundingBox.inflate(0.0))
            while (boxes.size > rollbackTicks.value) boxes.removeFirst()
        }
        val ids = level.players().map { it.id }
        rollback.keys.removeIf { it !in ids }
    }

    private fun grimEntity() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        recordRollback()
        if (!Movement.moving()) return
        var count = 0
        val near = level.entitiesForRendering().filter {
            it != p && (it !is ArmorStand || armorStands.value) && (it is LivingEntity || it is Boat)
        }
        for (e in near) {
            val reach = p.boundingBox.inflate(distance.value.toDouble())
            val touching = reach.intersects(e.boundingBox)
            val rolledBack = rollback.any { (id, boxes) -> id == e.id && boxes.any { p.boundingBox.inflate(distance.value.toDouble()).intersects(it) } }
            if (rolledBack || touching) count++
        }
        val boost = if (p.onGround()) groundSpeed.value else speed.value
        val yaw = when (boostAngle.value) {
            BoostAngle.SERVER -> RotationManager.peek(true).yaw
            BoostAngle.CLIENT -> p.yRot
            BoostAngle.PREDICT -> {
                val aura = Modules.get<Aura>()
                val t = aura.target
                if (aura.enabled && t != null) {
                    val ticks = predictTicks.value.toDouble()
                    Rotation.lookAt(t.position().add(Vec3(t.x - t.xo, t.y - t.yo, t.z - t.zo).multiply(ticks, ticks, ticks))).yaw
                } else {
                    RotationManager.peek(true).yaw
                }
            }
        }
        val dir = Movement.direction(boost / 100.0f * count.toFloat(), p.input.moveVector.y, p.input.moveVector.x, yaw)
        p.push(dir[0], 0.0, dir[1])
    }

    /** -2 when an elytra (damage < 430) is worn, else the first such elytra's slot id, else -1. */
    private fun elytraSlot(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in intArrayOf(39, 38, 37, 36)) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return -2
        }
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return if (i < 9) i + 36 else i
        }
        return -1
    }

    /** The elytra swap for the firework: pick the elytra up, click the chest slot, put it back and close. */
    private fun swapElytra(slot: Int) {
        containerClick(slot)
        containerClick(6)
        containerClick(slot, close = true)
    }

    private fun firework() {
        val p = mc.player ?: return
        counter--
        val elytra = elytraSlot()
        val rocket = Inventory.hotbarFind(Items.FIREWORK_ROCKET)
        val offhand = p.offhandItem.item == Items.FIREWORK_ROCKET
        if (rocket == null) {
            Inventory.find(Items.FIREWORK_ROCKET)?.let { containerClick(it.slot, fireSlot.value - 1, ContainerInput.SWAP) }
        }
        if (elytra == -1) return
        if (rocket == null && !offhand) return
        if (p.onGround() || PlayerState.fallDistance <= 0.0 || counter > 0) return
        if (elytra != -2) swapElytra(elytra)
        ElytraSwapHook.moduleStart = true
        Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        p.startFallFlying()
        val prev = p.inventory.selectedSlot
        val rocketSlot = rocket?.slot ?: -1
        if (prev != rocketSlot && !offhand) Inventory.selectHotbar(rocketSlot)
        mc.gameMode?.useItem(p, if (offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)
        if (prev != rocketSlot && !offhand) Inventory.selectHotbar(prev)
        if (elytra != -2) swapElytra(elytra)
        counter = delay.value
    }

    init {
        Movement.init()
        on<PreSyncEvent> {
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (paused() || mode.value != Mode.MATRIX) return@on
            val nearBlock = level.getBlockCollisions(p, p.boundingBox.inflate(0.5, 0.0, 0.5).move(0.0, -1.0, 0.0)).any { it != Shapes.empty() }
            if (Movement.moving() && nearBlock && PlayerState.fallDistance <= 0.0) {
                p.setOnGround(true)
                p.jumpFromGround()
                return@on
            }
            if (PlayerState.fallDistance <= 0.0) return@on
            if (useTimer.value) {
                TimerManager.factor = 1.088f
                p.push(0.0, -0.003, 0.0)
            }
        }
        on<PlayerUpdateEvent>(-10) {
            if (paused()) return@on
            if (mode.value == Mode.GRIM_ENTITY) grimEntity()
            if (mode.value == Mode.FIREWORK) firework()
        }
        on<MoveEvent> { e ->
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (p.abilities.flying || p.isFallFlying || p.foodData.foodLevel <= 6 || e.cancelled) return@on
            if (paused()) return@on
            val m = mode.value
            if (m == Mode.NCP || m == Mode.STRICT_STRAFE) {
                if (Movement.moving()) ncpHop(e) else {
                    e.x = 0.0
                    e.z = 0.0
                }
                e.cancel()
            }
            if (m == Mode.OLD_MATRIX) {
                if (PlayerState.fallDistance <= 0.5 || p.onGround()) return@on
                if (level.getBlockCollisions(p, p.boundingBox.inflate(-0.299, 0.0, -0.299).move(0.0, e.y, 0.0)).iterator().hasNext()) {
                    e.x *= 2
                    e.z *= 2
                    p.deltaMovement = p.deltaMovement.multiply(2.0, 1.0, 2.0)
                    e.cancel()
                }
            }
        }
    }

    /** The NCP / Strict Strafe hop. */
    private fun ncpHop(e: MoveEvent) {
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (useTimer.value) TimerManager.factor = 1.088f
        val last = if (mode.value == Mode.NCP && p.input.moveVector.y <= 0.0f && lastForward > 0.0f) Movement.lastHorizontal * 0.66f
        else Movement.lastHorizontal
        val canJump = !(p.horizontalCollision && Modules.get<Step>().enabled)
        if (stage == 1 && p.onGround() && canJump) {
            val jump = Movement.jumpMotion()
            p.setDeltaMovement(p.deltaMovement.x, jump, p.deltaMovement.z)
            e.y = Movement.jumpMotion()
            hopSpeed *= 2.149
            stage = 2
        } else if (stage == 2) {
            hopSpeed = last - 0.66 * (last - Movement.baseSpeed())
            stage = 3
        } else {
            if (level.getBlockCollisions(p, p.boundingBox.move(0.0, p.deltaMovement.y(), 0.0)).iterator().hasNext() || p.verticalCollision) stage = 1
            hopSpeed = last - last / 159.0
        }
        hopSpeed = max(hopSpeed, Movement.baseSpeed())
        val full = mode.value != Mode.STRICT_STRAFE && p.input.moveVector.y >= 1.0f
        var capLong = if (full) 0.576 else 0.465
        var capShort = if (full) 0.57 else 0.44
        p.getEffect(MobEffects.SPEED)?.let {
            val amp = it.amplifier.toDouble()
            capLong *= 1 + 0.2 * (amp + 1)
            capShort *= 1 + 0.2 * (amp + 1)
        }
        p.getEffect(MobEffects.SLOWNESS)?.let {
            val amp = it.amplifier.toDouble()
            capLong /= 1 + 0.2 * (amp + 1)
            capShort /= 1 + 0.2 * (amp + 1)
        }
        hopSpeed = min(hopSpeed, if (counter > 25) capLong else capShort)
        if (counter++ > 50) counter = 0
        Movement.strafe(e, hopSpeed)
        lastForward = p.input.moveVector.y
    }
}
