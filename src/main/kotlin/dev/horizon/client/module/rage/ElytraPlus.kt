package dev.horizon.client.module.rage

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.combat.ElytraFirework
import dev.horizon.client.combat.ElytraSwapHook
import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MoveFixKind
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.combat.TimerManager
import dev.horizon.client.combat.disableWith
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.AfterSendPacketEvent
import dev.horizon.client.event.ElytraTravelEvent
import dev.horizon.client.event.FireworkVelocityEvent
import dev.horizon.client.event.MoveEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreSyncEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SetPoseEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.setting.Bind
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every handler forwards to the selected mode's handler:
 * - `FIRE_WORK`: auto-equips elytra on fall, fires fireworks on `START_FALL_FLYING` and [fireDelay], strafes at
 *   [xZSpeed]/[ySpeed] with [antiKick] and [groundSafe], and restores chestplate/firework slots on disable.
 * - `BOOST`: pitch/glide physics boost with optional [cruiseControl], [vanilla] boost, [lift] bind, and
 *   [speedLimit].
 * - `CONTROL`: speed-set flight with up/down keys and instant re-deploy.
 * - `INFINITE`: pitch swing that trades height for speed.
 * - `PACKET`: creative-style flight while `START_FALL_FLYING` is resent every tick, with NCP-strict vertical spoofs.
 * - `SILENT_FIRE_WORK`: silent elytra swap + firework bursts without staying in fall-flying state, with custom
 *   pitch-based firework velocity boost.
 */
class ElytraPlus : Module("elytra-plus", Categories.RAGE) {
    enum class Mode { FIRE_WORK, BOOST, CONTROL, INFINITE, PACKET, SILENT_FIRE_WORK }
    @Suppress("EnumEntryName")
    enum class NcpStrict { Off, Old, New, Motion }
    @Suppress("EnumEntryName")
    enum class AntiKick { Off, Jitter, Glide }
    enum class StopMove { OFF, TIMER, BLOCK }

    val mode = enum("mode", Mode.FIRE_WORK)
    val vanilla = bool("vanilla", false) { mode.value == Mode.BOOST }
    val lift = bind("lift", Bind()) { mode.value == Mode.BOOST && vanilla.value }
    val onlySpace = bool("only-space", true) { mode.value == Mode.BOOST && vanilla.value }
    val stopOnGround = bool("stop-on-ground", false) { mode.value == Mode.PACKET }
    val infDurability = bool("inf-durability", true) { mode.value == Mode.PACKET }
    val vertical = bool("vertical", false) { mode.value == Mode.PACKET }
    val ncpStrict = enum("ncp-strict", NcpStrict.Off) { mode.value == Mode.PACKET }
    val antiKick = enum("anti-kick", AntiKick.Jitter) { mode.value == Mode.FIRE_WORK }
    val xZSpeed = float("x-z-speed", 1.55f, 0.1f..10.0f) { (mode.value == Mode.FIRE_WORK || mode.value == Mode.CONTROL || mode.value == Mode.PACKET) }
    val ySpeed = float("y-speed", 0.47f, 0.0f..2.0f) { mode.value == Mode.FIRE_WORK && !vertical.value || (mode.value == Mode.FIRE_WORK || mode.value == Mode.PACKET) && vertical.value }
    val fireSlot = int("fire-slot", 1, 1..9) { mode.value == Mode.FIRE_WORK }
    val acceleration = bool("acceleration", false) { (mode.value == Mode.CONTROL || mode.value == Mode.PACKET) }
    val accelerateFactor = float("accelerate-factor", 9.0f, 0.1f..10.0f) { (mode.value == Mode.CONTROL || mode.value == Mode.PACKET) && acceleration.value }
    val fireDelay = float("fire-delay", 1.5f, 0.0f..1.5f) { mode.value == Mode.FIRE_WORK }
    val rotate = bool("rotate", true) { mode.value == Mode.FIRE_WORK }
    val groundSafe = bool("ground-safe", false) { mode.value == Mode.FIRE_WORK }
    val keepFlying = bool("keep-flying", false) { mode.value == Mode.FIRE_WORK }
    val disableOnFlag = bool("disable-on-flag", false) { mode.value == Mode.FIRE_WORK }
    val allowFireSwap = bool("allow-fire-swap", false) { mode.value == Mode.FIRE_WORK }
    val instantFly = bool("instant-fly", true) { (mode.value == Mode.BOOST || mode.value == Mode.CONTROL) && !vanilla.value || mode.value == Mode.CONTROL && vanilla.value }
    val cruiseControl = bool("cruise-control", false) { mode.value == Mode.BOOST }
    val pitchControl = bool("pitch-control", false) { (mode.value == Mode.CONTROL || mode.value == Mode.SILENT_FIRE_WORK) }
    val factor = float("factor", 1.5f, 0.1f..50.0f) { mode.value == Mode.BOOST }
    val upSpeed = float("up-speed", 1.0f, 0.01f..5.0f) { (mode.value == Mode.BOOST || mode.value == Mode.CONTROL) && !vanilla.value || mode.value == Mode.CONTROL && vanilla.value }
    val glide = float("glide", 1.0f, 0.0f..2.0f) { (mode.value == Mode.BOOST || mode.value == Mode.CONTROL) && !vanilla.value || mode.value == Mode.CONTROL && vanilla.value }
    val stopMotion = bool("stop-motion", true) { mode.value == Mode.BOOST && !vanilla.value }
    val minUpSpeed = float("min-up-speed", 0.5f, 0.1f..5.0f) { mode.value == Mode.BOOST && cruiseControl.value }
    val forceHeight = bool("force-height", false) { mode.value == Mode.BOOST && cruiseControl.value }
    val height = int("height", 121, 1..256) { mode.value == Mode.BOOST && forceHeight.value }
    val downSpeed = float("down-speed", 1.0f, 0.01f..5.0f) { mode.value == Mode.CONTROL }
    val speedLimit = bool("speed-limit", true) { mode.value == Mode.BOOST }
    val maxSpeed = float("max-speed", 2.5f, 0.1f..10.0f) { mode.value == Mode.BOOST }
    val redeployInterval = float("redeploy-interval", 1.0f, 0.1f..5.0f) { mode.value == Mode.BOOST }
    val redeployTimeout = float("redeploy-timeout", 5.0f, 0.1f..20.0f) { mode.value == Mode.BOOST }
    val redeployDelay = float("redeploy-delay", 0.5f, 0.1f..1.0f) { mode.value == Mode.BOOST }
    val infiniteMaxHeight = int("infinite-max-height", 200, 50..360) { mode.value == Mode.INFINITE }
    val stopMove = enum("stop-move", StopMove.BLOCK) { mode.value == Mode.SILENT_FIRE_WORK }
    val elytraDelay = int("elytra-delay", 2, 0..40) { mode.value == Mode.SILENT_FIRE_WORK }
    val fireworkDelay = int("firework-delay", 8, 0..40) { mode.value == Mode.SILENT_FIRE_WORK }
    val offhandFirework = bool("offhand-firework", true) { mode.value == Mode.SILENT_FIRE_WORK }
    val altFireworkSwap = bool("alt-firework-swap", false) { mode.value == Mode.SILENT_FIRE_WORK && offhandFirework.value }
    val pauseWhileEating = bool("pause-while-eating", true) { mode.value == Mode.SILENT_FIRE_WORK }
    val minimizeClicks = bool("minimize-clicks", false) { mode.value == Mode.SILENT_FIRE_WORK }
    val boost = bool("boost", false) { mode.value == Mode.SILENT_FIRE_WORK }
    val customSpeed = bool("custom-speed", false) { mode.value == Mode.SILENT_FIRE_WORK && boost.value }
    val n0Degrees = float("0-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n5Degrees = float("5-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n10Degrees = float("10-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n15Degrees = float("15-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n20Degrees = float("20-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n25Degrees = float("25-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n30Degrees = float("30-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n35Degrees = float("35-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n40Degrees = float("40-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n45Degrees = float("45-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n50Degrees = float("50-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n55Degrees = float("55-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n60Degrees = float("60-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n65Degrees = float("65-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n70Degrees = float("70-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n75Degrees = float("75-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n80Degrees = float("80-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n85Degrees = float("85-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }
    val n90Degrees = float("90-degrees", 0.15f, 0.0f..1.0f) { mode.value == Mode.SILENT_FIRE_WORK && boost.value && customSpeed.value }

    /** Re-deploy armed by touching the ground. */
    private var redeployArmed = false

    /** Whether FIRE_WORK swapped an elytra onto the chest slot. */
    private var elytraSwapped = false

    /** CONTROL / FIRE_WORK / PACKET acceleration, in percent of [xZSpeed]. */
    private var accel = 0f

    /** BOOST's target cruise altitude. */
    private var cruiseHeight = 0f

    /** The pitch INFINITE puts on the player around travel, and the real one meanwhile. */
    private var targetPitch = 0f
    private var savedPitch = 0f

    /** INFINITE: climbing phase and the pitch it swings. */
    private var climbing = false
    private var swing = 0f

    /** Timers for instant re-deploy. */
    private val redeployTimer = MsTimer()
    private val secondTimer = MsTimer()

    // --- FIRE_WORK state ---
    private var fwStarted = false
    private var fwSentStartFlying = false
    private var fwYAccel = 0f
    private var fwPrevArmorStack: ItemStack? = null
    private var fwPrevFireworkSlotStack: ItemStack? = null
    private var fwPrevArmorItem: Item = Items.AIR
    private var fwPrevFireworkSlotItem: Item = Items.AIR
    private var fwElytraSlot = -1
    private var fwLastFireworkSlot = -1
    private var fwLastFireworkTime = 0L

    // --- BOOST state ---
    private val boostTimeoutTimer = MsTimer()
    private val boostLiftTimer = MsTimer()

    // --- SILENT_FIRE_WORK state ---
    private var silentSavedYaw = 0f
    private var silentSavedPitch = 0f
    private val silentFireworkTimer = TickTimer()
    private val silentElytraTimer = TickTimer()
    private val silentCustomTable = HashMap<Float, Float>()

    /** Ground just under the feet. */
    private fun groundBelow(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        return level.getBlockCollisions(p, p.boundingBox.inflate(-0.25, 0.0, -0.25).move(0.0, -0.3, 0.0)).iterator().hasNext()
    }

    private fun chestElytra() = mc.player?.inventory?.getItem(38)?.`is`(Items.ELYTRA) == true

    /** Usable glider in slot. */
    private fun isUsableElytra(stack: ItemStack): Boolean =
        stack.damageValue < stack.maxDamage - 1 && stack.has(DataComponents.GLIDER)

    override fun onDisable() {
        val p = mc.player ?: return
        if (mc.level == null) return
        when (mode.value) {
            Mode.PACKET -> p.abilities.let {
                it.flying = false
                it.flyingSpeed = 0.05f
            }
            Mode.FIRE_WORK -> fireworkDisable()
            else -> {}
        }
    }

    override fun onEnable() {
        val p = mc.player ?: return
        if (mc.level == null) return
        accel = 0f
        cruiseHeight = p.y.toFloat()
        when (mode.value) {
            Mode.FIRE_WORK -> fireworkEnable()
            Mode.INFINITE -> {
                if (p.y < infiniteMaxHeight.value) {
                    disable()
                    moduleMessage(name.trim(), "Go above ${infiniteMaxHeight.value}")
                }
                climbing = false
            }
            else -> {}
        }
    }

    // ========================================================================
    // Mode.FIRE_WORK
    // ========================================================================

    private fun findFirework(hotbarOnly: Boolean): Int =
        if (hotbarOnly) Inventory.hotbarFind(Items.FIREWORK_ROCKET)?.slot ?: -1
        else Inventory.find(Items.FIREWORK_ROCKET)?.slot ?: -1

    private fun noFireworks() {
        disableWith("You've got no fireworks!")
        fwStarted = false
    }

    private fun noElytra() {
        disableWith("You've got no elytra!")
        fwStarted = false
    }

    private fun moveFireworkToHotbar(slot: Int) {
        Inventory.click(slot, 0, ContainerInput.PICKUP, false)
        Inventory.click(fireSlot.value - 1 + 36, 0, ContainerInput.PICKUP, false)
        Inventory.click(slot, 0, ContainerInput.PICKUP, true)
    }

    private fun getFireworkHotbarSlot(): Int {
        val p = mc.player ?: return -1
        if (p.offhandItem.item == Items.FIREWORK_ROCKET) return -2
        val hot = findFirework(true)
        val inv = findFirework(false)
        if (inv == -1) {
            noFireworks()
            return -1
        }
        if (hot != -1) return hot
        if (!allowFireSwap.value) {
            disableWith("You've got no fireworks!")
            return fireSlot.value - 1
        }
        moveFireworkToHotbar(inv)
        return fireSlot.value - 1
    }

    private fun useFirework(checkDelay: Boolean) {
        val p = mc.player ?: return
        if (checkDelay && (System.currentTimeMillis() - fwLastFireworkTime) < fireDelay.value * 1000.0f) return
        if (checkDelay && !p.isFallFlying) return
        if (!checkDelay && PlayerState.elytraTicks > 1) return
        val slot = getFireworkHotbarSlot()
        if (slot == -1) {
            fwLastFireworkSlot = -1
            return
        }
        fwLastFireworkSlot = slot
        val offhand = p.offhandItem.item == Items.FIREWORK_ROCKET
        val prevSlot = p.inventory.selectedSlot
        if (!offhand && prevSlot != slot) Inventory.selectHotbar(slot)
        mc.gameMode?.useItem(p, if (offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND)
        if (!offhand && prevSlot != slot) Inventory.selectHotbar(prevSlot)
        fwStarted = true
        fwLastFireworkTime = System.currentTimeMillis()
    }

    private fun needElytraSwap(): Boolean {
        val chest = mc.player?.getItemBySlot(EquipmentSlot.CHEST) ?: return true
        return !(chest.item == Items.ELYTRA && isUsableElytra(chest))
    }

    private fun canFly(): Boolean {
        if (needElytraSwap()) return false
        return getFireworkHotbarSlot() != -1
    }

    private fun equipElytra() {
        val p = mc.player ?: return
        val slot = Slots.elytra()
        if (slot == -1 && p.containerMenu.carried.item != Items.ELYTRA) {
            noElytra()
            return
        }
        if (!needElytraSwap()) return
        if (fwElytraSlot == -1) {
            val chest = p.getItemBySlot(EquipmentSlot.CHEST)
            fwElytraSlot = slot
            fwPrevArmorItem = chest.item
            fwPrevArmorStack = chest.copy()
        }
        Inventory.click(slot, 0, ContainerInput.PICKUP, false)
        Inventory.click(6, 0, ContainerInput.PICKUP, false)
        if (fwElytraSlot != -1) {
            Inventory.click(fwElytraSlot, 0, ContainerInput.PICKUP, false)
        }
        elytraSwapped = true
    }

    private fun findMatchingStack(stack: ItemStack?, item: Item): Int {
        if (stack == null) return -1
        val inv = mc.player?.inventory ?: return -1
        for (i in 0 until minOf(45, inv.containerSize)) {
            val s = inv.getItem(i)
            if (ItemStack.isSameItem(s, stack) && s.item == item) return i
        }
        return -1
    }

    private fun findEmptySlot(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 9 until minOf(45, inv.containerSize)) {
            if (inv.getItem(i).isEmpty) return i
        }
        return -1
    }

    private fun restoreFireworkSlot() {
        if (fwLastFireworkSlot != -1 && fwPrevFireworkSlotStack != null) {
            if (fwPrevFireworkSlotItem == Items.FIREWORK_ROCKET || fwPrevFireworkSlotItem == Items.AIR) return
            var slot = findMatchingStack(fwPrevFireworkSlotStack, fwPrevFireworkSlotItem)
            if (slot in 0..8) slot += 36
            if (slot != -1) {
                Inventory.click(slot, 0, ContainerInput.PICKUP, false)
                Inventory.click(fireSlot.value - 1 + 36, 0, ContainerInput.PICKUP, false)
                Inventory.click(slot, 0, ContainerInput.PICKUP, false)
            }
        }
    }

    private fun resetArmorState() {
        fwElytraSlot = -1
        fwPrevArmorItem = Items.AIR
        fwPrevArmorStack = null
    }

    private fun restoreChestplate() {
        val p = mc.player ?: return
        if (fwElytraSlot != -1 && fwPrevArmorItem != Items.AIR) {
            if (!elytraSwapped) return
            val prevStack = fwPrevArmorStack ?: return
            val curAtSlot = if (fwElytraSlot in 0 until p.inventory.containerSize) p.inventory.getItem(fwElytraSlot) else ItemStack.EMPTY
            val slotChanged = !(curAtSlot === ItemStack.EMPTY || ItemStack.isSameItem(curAtSlot, prevStack))
            var oldSlot = findMatchingStack(prevStack, fwPrevArmorItem)
            if (oldSlot in 0..8) oldSlot += 36
            if (p.containerMenu.carried.item != Items.AIR) {
                Inventory.click(6, 0, ContainerInput.PICKUP, false)
                if (fwElytraSlot == -1) return
                Inventory.click(fwElytraSlot, 0, ContainerInput.PICKUP, false)
                return
            }
            if (oldSlot == -1) return
            Inventory.click(oldSlot, 0, ContainerInput.PICKUP, false)
            Inventory.click(6, 0, ContainerInput.PICKUP, false)
            if (!slotChanged) {
                Inventory.click(oldSlot, 0, ContainerInput.PICKUP, false)
            } else {
                val empty = findEmptySlot()
                if (empty != -1) Inventory.click(empty, 0, ContainerInput.PICKUP, false)
            }
        }
        resetArmorState()
    }

    private fun noGroundBelow3(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (p.y < 0.0) return false
        return !level.getBlockCollisions(p, p.boundingBox.move(0.0, -3.0, 0.0)).iterator().hasNext()
    }

    private fun inLiquidBelow(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        return level.getBlockState(BlockPos.containing(p.x, p.y - 0.1, p.z)).block is LiquidBlock
    }

    private fun fireworkEnable() {
        val p = mc.player ?: return
        fwYAccel = 0f
        fwLastFireworkSlot = -1
        fwPrevFireworkSlotItem = Items.AIR
        fwPrevFireworkSlotStack = null
        fwStarted = false
        if (p.getItemBySlot(EquipmentSlot.CHEST).item != Items.ELYTRA && p.containerMenu.carried.item != Items.ELYTRA && Slots.elytra() == -1) {
            noElytra()
            return
        }
        if (findFirework(false) == -1) {
            noFireworks()
            return
        }
        if (findFirework(true) == -1) {
            val cur = p.inventory.getItem(fireSlot.value - 1)
            fwPrevFireworkSlotStack = cur.copy()
            fwPrevFireworkSlotItem = cur.item
        }
    }

    private fun fireworkDisable() {
        val p = mc.player ?: return
        fwSentStartFlying = false
        if (keepFlying.value) return
        p.setDeltaMovement(0.0, p.deltaMovement.y, 0.0)
        Thread {
            mc.execute {
                val pl = mc.player ?: return@execute
                Packets.send(ServerboundPlayerCommandPacket(pl, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                restoreFireworkSlot()
                fwLastFireworkSlot = -1
                fwPrevFireworkSlotItem = Items.AIR
                fwPrevFireworkSlotStack = null
                restoreChestplate()
                resetArmorState()
            }
        }.start()
    }

    private fun fireworkPreSync() {
        val p = mc.player ?: return
        if (rotate.value) {
            if (mc.options.keyJump.isDown && p.isFallFlying && fwStarted) p.xRot = -45.0f
            if (mc.options.keyShift.isDown && p.isFallFlying && fwStarted) p.xRot = 45.0f
            p.yRot = Movement.keysYaw()
        }
        if (!Movement.moving() && mc.options.keyJump.isDown && p.isFallFlying && fwStarted) {
            p.xRot = -90.0f
        }
        if (PlayerState.elytraTicks < 5 && !p.onGround()) {
            p.xRot = -45.0f
        }
    }

    private fun fireworkMove(e: MoveEvent) {
        val p = mc.player ?: return
        if (!p.isFallFlying || !fwStarted) return
        if (p.horizontalCollision || p.verticalCollision) {
            accel = 0f
            fwYAccel = 0f
        }
        if (PlayerState.elytraTicks < 4) {
            e.y = 0.2
            e.cancel()
            return
        }
        if (mc.options.keyJump.isDown) {
            fwYAccel += 9.0f
            e.y = ySpeed.value * min((fwYAccel / 100.0f).toDouble(), 1.0)
        } else if (mc.options.keyShift.isDown) {
            fwYAccel += 9.0f
            e.y = -ySpeed.value * min((fwYAccel / 100.0f).toDouble(), 1.0)
        } else {
            e.y = when (antiKick.value) {
                AntiKick.Jitter -> if (p.tickCount % 2 == 0) 0.08 else -0.08
                AntiKick.Glide -> -0.08
                AntiKick.Off -> 0.0
            }
        }
        if (!Movement.moving()) accel = 0f
        val mv = p.input.moveVector
        if (mv.x > 0.0f) {
            (p.input as ClientInputAccessor).horizon_setMoveVector(Vec2(1.0f, mv.y))
        } else if (mv.x < 0.0f) {
            (p.input as ClientInputAccessor).horizon_setMoveVector(Vec2(-1.0f, mv.y))
        }
        accel += 9.0f
        Movement.strafe(e, xZSpeed.value * min((accel / 100.0f).toDouble(), 1.0))
        if (groundSafe.value && !noGroundBelow3() && PlayerState.elytraTicks > 10) {
            e.y = 0.42
        }
        e.cancel()
    }

    private fun fireworkUpdate() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val emptyAtFeet = level.isEmptyBlock(BlockPos.containing(p.position()))
        val fallingFromLiquid = inLiquidBelow() && emptyAtFeet && p.deltaMovement.y < 0.0
        if ((PlayerState.fallDistance > 0.0 && emptyAtFeet) || fallingFromLiquid) {
            equipElytra()
        } else if (p.onGround()) {
            fwSentStartFlying = false
            return
        }
        if (!Movement.moving()) accel = 0f
        if (!canFly()) return
        if (!p.isFallFlying && !fwSentStartFlying && p.deltaMovement.y < 0.0) {
            Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
            fwSentStartFlying = true
        }
        if (PlayerState.elytraTicks < 4) {
            mc.options.keyJump.isDown = false
        }
        useFirework(true)
    }

    // ========================================================================
    // Mode.BOOST
    // ========================================================================

    private fun boostPreSync() {
        val p = mc.player ?: return
        if (vanilla.value) return
        if (p.onGround()) redeployArmed = true
        if (!cruiseControl.value) cruiseHeight = p.y.toFloat()
        if (p.isFallFlying || !chestElytra()) return
        if (redeployArmed && !p.onGround() && PlayerState.fallDistance > 0.0 && instantFly.value) {
            TimerManager.factor = 0.3f
        }
        if (p.onGround() || !instantFly.value) return
        if (p.deltaMovement.y < 0.0 && redeployTimer.passed((1000.0f * redeployDelay.value).toInt().toLong())) {
            redeployTimer.reset()
            ElytraSwapHook.moduleStart = true
            Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
            redeployArmed = false
            secondTimer.reset()
        }
    }

    private fun boostMove(e: MoveEvent) {
        val p = mc.player ?: return
        if (!chestElytra() || !p.isFallFlying || p.isInWater || p.isInLava) return
        var forward = p.input.moveVector.y
        if (cruiseControl.value) {
            if (mc.options.keyJump.isDown) {
                cruiseHeight += 1.0f
            } else if (mc.options.keyShift.isDown) {
                cruiseHeight += -1.0f
            }
            if (forceHeight.value) {
                cruiseHeight = height.value.toFloat()
            }
            if (vanilla.value) {
                if (Movement.lastHorizontal >= minUpSpeed.value) {
                    p.xRot = Mth.clamp(Mth.wrapDegrees(Math.toDegrees(atan2((cruiseHeight - p.y) * -1.0, 10.0))), -50.0, 50.0).toFloat()
                } else {
                    p.xRot = 0.25f
                }
            } else {
                val d = 1.0 - sqrt(Mth.clamp(Movement.lastHorizontal / 1.7, 0.0, 1.0))
                if (Movement.lastHorizontal >= minUpSpeed.value && redeployTimer.passed((2000.0f * redeployInterval.value).toInt().toLong())) {
                    val basePitch = -(44.4 * d + 0.6)
                    val diff = (cruiseHeight + 1.0f - p.y) * 2.0
                    val targetPitch = -Math.toDegrees(atan2(abs(diff), Movement.lastHorizontal * 30.0)) * sign(diff)
                    p.xRot = (basePitch + (targetPitch - basePitch) * Mth.clamp(abs(diff), 0.0, 1.0)).toFloat()
                } else {
                    p.xRot = 0.25f
                    forward = 1.0f
                }
            }
        }
        if (vanilla.value) {
            if ((mc.options.keyJump.isDown || !onlySpace.value) && !cruiseControl.value) {
                val m = Movement.forward((factor.value / 10.0f).toDouble())
                e.x += m[0]
                e.z += m[1]
                e.y += 0.003
            }
        } else {
            val look = p.getViewVector(mc.deltaTracker.getGameTimeDeltaPartialTick(true))
            val horizLook = hypot(look.x, look.z)
            val horizMove = hypot(e.x, e.z)
            val cosSq = (cos(Math.toRadians(p.xRot.toDouble())).pow(2.0) * min(1.0, look.length() / 0.4)).toFloat()
            e.y += -0.08 + (cosSq * 0.06f).toDouble()
            if (e.y < 0.0 && horizLook > 0.0) {
                val lift = e.y * -0.1 * cosSq
                e.y += lift
                e.x += look.x * lift / horizLook
                e.z += look.z * lift / horizLook
            }
            if (p.xRot < 0.0f && horizLook > 0.0) {
                val climb = horizMove * -sin(Math.toRadians(p.xRot.toDouble())) * 0.04
                e.y += climb * 3.2
                e.x -= look.x * climb / horizLook
                e.z -= look.z * climb / horizLook
            }
            if (horizLook > 0.0) {
                e.x += (look.x / horizLook * horizMove - e.x) * 0.1
                e.z += (look.z / horizLook * horizMove - e.z) * 0.1
            }
            if (p.xRot > 0.0f && e.y < 0.0 && chestElytra()) {
                val intervalMs = (2000.0f * redeployInterval.value).toInt().toLong()
                val timeoutMs = (1000.0f * redeployTimeout.value).toInt().toLong()
                if (forward != 0.0f && redeployTimer.passed(intervalMs) && boostTimeoutTimer.passed(timeoutMs)) {
                    if (stopMotion.value) {
                        e.x = 0.0
                        e.z = 0.0
                    }
                    redeployTimer.reset()
                    Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                } else if (!redeployTimer.passed(intervalMs)) {
                    e.x -= forward * sin(Math.toRadians(p.yRot.toDouble())) * factor.value / 20.0f
                    e.z += forward * cos(Math.toRadians(p.yRot.toDouble())) * factor.value / 20.0f
                    boostTimeoutTimer.reset()
                }
            }
        }
        val speed = hypot(e.x, e.z)
        if (speedLimit.value && speed > maxSpeed.value) {
            e.x = e.x * maxSpeed.value / speed
            e.z = e.z * maxSpeed.value / speed
        }
        p.setDeltaMovement(e.x, e.y, e.z)
        e.cancel()
    }

    private fun boostUpdate() {
        val p = mc.player ?: return
        if (vanilla.value && EquipUtil.held(lift.value) && PlayerState.fallDistance > 0.0) {
            if (!p.isFallFlying) {
                ElytraSwapHook.moduleStart = true
                Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                p.startFallFlying()
            }
            val addY = 0.05 * min(boostLiftTimer.elapsed.toFloat() / 100.0f, 1.0f)
            p.setDeltaMovement(0.0, min(p.deltaMovement.y + addY, 1.0), 0.0)
        } else {
            boostLiftTimer.reset()
        }
    }

    // ========================================================================
    // Mode.SILENT_FIRE_WORK
    // ========================================================================

    private fun anyMoveKeyDown(): Boolean {
        val o = mc.options
        return o.keyUp.isDown || o.keyLeft.isDown || o.keyRight.isDown || o.keyDown.isDown || o.keyJump.isDown || o.keyShift.isDown
    }

    private fun silentTargetYaw(): Float = Movement.keysYaw()

    private fun silentTargetPitch(): Float {
        val p = mc.player ?: return 0f
        if (mc.options.keyJump.isDown) return -90.0f
        if (mc.options.keyShift.isDown) return 90.0f
        return if (pitchControl.value) p.xRot else 0.0f
    }

    private fun fillSilentCustomTable() {
        silentCustomTable[0f] = n0Degrees.value
        silentCustomTable[5f] = n5Degrees.value
        silentCustomTable[10f] = n10Degrees.value
        silentCustomTable[15f] = n15Degrees.value
        silentCustomTable[20f] = n20Degrees.value
        silentCustomTable[25f] = n25Degrees.value
        silentCustomTable[30f] = n30Degrees.value
        silentCustomTable[35f] = n35Degrees.value
        silentCustomTable[40f] = n40Degrees.value
        silentCustomTable[45f] = n45Degrees.value
        silentCustomTable[50f] = n50Degrees.value
        silentCustomTable[55f] = n55Degrees.value
        silentCustomTable[60f] = n60Degrees.value
        silentCustomTable[65f] = n65Degrees.value
        silentCustomTable[70f] = n70Degrees.value
        silentCustomTable[75f] = n75Degrees.value
        silentCustomTable[80f] = n80Degrees.value
        silentCustomTable[85f] = n85Degrees.value
        silentCustomTable[90f] = n90Degrees.value
    }

    private fun silentFireworkUpdate(e: PlayerUpdateEvent) {
        val p = mc.player ?: return
        if (!anyMoveKeyDown()) {
            if (stopMove.value == StopMove.BLOCK) {
                e.cancel()
                return
            }
            if (stopMove.value == StopMove.TIMER) {
                TimerManager.factor = 0.05f
            }
        }
        val elytraSlot = Slots.elytra()
        val fw = Inventory.find(Items.FIREWORK_ROCKET)
        if (elytraSlot < 0 || fw == null || p.isInWater || p.isInLava) return
        if (pauseWhileEating.value && p.isUsingItem &&
            (p.mainHandItem.components.has(DataComponents.FOOD) || p.mainHandItem.components.has(DataComponents.POTION_CONTENTS))
        ) {
            return
        }
        if (p.onGround()) {
            if (mc.options.keyJump.isDown) return
            mc.options.keyJump.isDown = true
            Scheduler.ticks(3) {
                if (!Keys.down(InputConstants.KEY_SPACE)) {
                    mc.options.keyJump.isDown = false
                }
            }
            return
        }
        if (!p.isFallFlying && silentElytraTimer.passedAndReset(elytraDelay.value)) {
            val guiMove = Modules.get<GuiMove>()
            if (elytraSlot >= 36) {
                guiMove.internalClick = true
                val hotbarIdx = elytraSlot - 36
                Inventory.swap(6, hotbarIdx, true)
                ElytraSwapHook.moduleStart = true
                p.startFallFlying()
                Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                if (silentFireworkTimer.passedAndReset(fireworkDelay.value)) {
                    ElytraFirework.use(offhandFirework.value, altFireworkSwap.value)
                }
                Inventory.swap(6, hotbarIdx, true)
                guiMove.internalClick = false
            } else {
                guiMove.internalClick = true
                Inventory.click(elytraSlot, 0, ContainerInput.PICKUP, false)
                Inventory.click(6, 0, ContainerInput.PICKUP, false)
                if (!minimizeClicks.value) {
                    Inventory.click(elytraSlot, 0, ContainerInput.PICKUP, true)
                }
                ElytraSwapHook.moduleStart = true
                p.startFallFlying()
                Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
                if (silentFireworkTimer.passedAndReset(fireworkDelay.value)) {
                    ElytraFirework.use(offhandFirework.value, altFireworkSwap.value)
                }
                if (!minimizeClicks.value) {
                    Inventory.click(elytraSlot, 0, ContainerInput.PICKUP, false)
                }
                Inventory.click(6, 0, ContainerInput.PICKUP, false)
                Inventory.click(elytraSlot, 0, ContainerInput.PICKUP, true)
                guiMove.internalClick = false
            }
        }
        RotationManager.rotate(Rotation(silentTargetYaw(), silentTargetPitch(), false, MoveFixKind.FORCE), 0)
    }

    private fun silentFireworkVelocity(e: FireworkVelocityEvent) {
        val p = mc.player ?: return
        fillSilentCustomTable()
        if (!boost.value) return
        val rot = RotationManager.current ?: Rotation(p.yRot, p.xRot)
        val look = Vec3.directionFromRotation(rot.pitch, rot.yaw)
        val d = 1.5
        var speed = 0.15
        if (customSpeed.value) {
            val entry = silentCustomTable.entries.minByOrNull { abs(it.key - abs(rot.pitch)) }
            if (entry != null) speed = entry.value.toDouble()
        } else {
            val ys = arrayOf(15, 75, -15, -75)
            val xs = arrayOf(45, 135, -45, -135)
            val pitchDist = ys.minOf { abs(it - rot.pitch) }
            val yawDist = xs.minOf { abs(it - Mth.wrapDegrees(rot.yaw)) }
            if (abs(rot.pitch) <= 30.0f) {
                speed = 0.18 + 0.08 * ((30.0f - yawDist) / 30.0f)
            } else if (pitchDist <= 10.0f) {
                speed = 0.18 + 0.05 * ((10.0f - pitchDist) / 10.0f)
            }
        }
        val cur = p.deltaMovement
        e.cancel()
        e.velocity = cur.add(
            look.x * speed + (look.x * d - cur.x) * 0.5,
            look.y * speed + (look.y * d - cur.y) * 0.5,
            look.z * speed + (look.z * d - cur.z) * 0.5,
        )
    }

    /** Arm on the ground, run 0.3 timer while falling, re-deploy instantly. */
    private fun controlPreSync() {
        val p = mc.player ?: return
        if (p.onGround()) redeployArmed = true
        if (p.isFallFlying || !chestElytra()) return
        if (redeployArmed && !p.onGround() && PlayerState.fallDistance > 0.0 && instantFly.value) TimerManager.factor = 0.3f
        if (p.onGround() || !instantFly.value) return
        if (p.deltaMovement.y() < 0.0) {
            if (!redeployTimer.passed((1000 * redeployDelay.value).toInt().toLong())) return
            redeployTimer.reset()
            ElytraSwapHook.moduleStart = true
            Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
            redeployArmed = false
        }
    }

    /** While gliding the move is set from the input, up/down keys and glide. */
    private fun controlMove(e: MoveEvent) {
        val p = mc.player ?: return
        if (!chestElytra() || !p.isFallFlying) return
        val factor = if (!acceleration.value) 1.0 else {
            accel += accelerateFactor.value
            min((accel / 100.0f).toDouble(), 1.0)
        }
        val dir = Movement.direction(xZSpeed.value * factor.toFloat(), p.input.moveVector.y, p.input.moveVector.x, p.yRot)
        e.x = dir[0]
        e.y = when {
            mc.options.keyJump.isDown -> upSpeed.value.toDouble()
            mc.options.keyShift.isDown -> -downSpeed.value.toDouble()
            else -> {
                val pitch = if (pitchControl.value && Movement.moving()) {
                    p.xRot / 90.0 * -upSpeed.value * sign(p.input.moveVector.y)
                } else 0.0
                pitch - 0.08 * glide.value
            }
        }
        e.z = dir[1]
        if (!Movement.moving()) accel = 0f
        p.setDeltaMovement(e.x, e.y, e.z)
        e.cancel()
    }

    /** Swing the pitch down (speed) or up (height), turning back at 25 / 150 km/h below the cap. */
    private fun infinitePitch(): Float {
        val p = mc.player ?: return swing
        val level = mc.level ?: return swing
        val kmh = Movement.lastHorizontal * 72.0f
        if (p.y < infiniteMaxHeight.value) {
            if (kmh < 25.0f && !climbing) climbing = true
            if (kmh - 150.0f > 0.0f && climbing) climbing = false
        } else {
            climbing = true
        }
        swing += if (climbing) 3.0f else -3.0f
        val eye = p.eyePosition
        val hit = level.clip(ClipContext(eye, eye.add(p.deltaMovement.x * 10.0, p.deltaMovement.y * 10.0, p.deltaMovement.z * 10.0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
        if (hit.type == HitResult.Type.BLOCK) {
            p.xRot = -33.0f
            disable()
        }
        swing = swing.coerceIn(-40.0f, 40.0f)
        return swing
    }

    /** The pitch for the packet, and a warning when the elytra is nearly broken. */
    private fun infinitePreSync() {
        val p = mc.player ?: return
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)
        if (!chest.`is`(Items.ELYTRA)) return
        p.xRot = targetPitch
        if (chest.damageValue > 380 && p.tickCount % 100 == 0) {
            Notifications.post("Elytra+", "Elytra's about to break!", 2f, NotificationType.WARNING)
            mc.level?.playSeededSound(p, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.AMBIENT, 10.0f, 1.0f, 0L)
        }
    }

    /** START_FALL_FLYING every tick (or when not gliding); NCP Motion skips 2 of 3 moves. */
    private fun packetPreSync(e: PreSyncEvent) {
        val p = mc.player ?: return
        if (groundBelow() && stopOnGround.value) return
        if (!chestElytra()) return
        if (infDurability.value || !p.isFallFlying) {
            ElytraSwapHook.moduleStart = true
            Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        }
        if (p.tickCount % 3 != 0 && ncpStrict.value == NcpStrict.Motion) e.cancel()
    }

    /** Abilities flight at [xZSpeed] / 15, vertical keys, NCP spoofs and a durability circle. */
    private fun packetMove(e: MoveEvent) {
        val p = mc.player ?: return
        p.abilities.flying = false
        p.abilities.flyingSpeed = 0.05f
        if (groundBelow() && stopOnGround.value) return
        if (!chestElytra()) return
        p.abilities.flying = true
        val base = (xZSpeed.value / 15.0f).toDouble()
        val factor = if (!acceleration.value) 1.0 else {
            accel += accelerateFactor.value
            min(accel / 100.0, 1.0)
        }
        p.abilities.flyingSpeed = (base * factor).toFloat()
        e.cancel()
        if (p.tickCount % 3 == 0 && ncpStrict.value == NcpStrict.Motion) {
            e.x = 0.0
            e.y = 0.0
            e.z = 0.0
            return
        }
        if (abs(e.x) < 0.05) e.x = 0.0
        if (abs(e.z) < 0.05) e.z = 0.0
        e.y = when {
            !vertical.value -> 0.0
            mc.options.keyJump.isDown -> ySpeed.value.toDouble()
            mc.options.keyShift.isDown -> -ySpeed.value.toDouble()
            else -> 0.0
        }
        when (ncpStrict.value) {
            NcpStrict.New -> e.y = (-1.0000889E-12f).toDouble()
            NcpStrict.Motion -> e.y = (-4.0003556E-12f).toDouble()
            NcpStrict.Old -> e.y = 2.0E-4 - (if (p.tickCount % 2 == 0) 0.0 else 1.0E-6) + Math.random() * 9.0E-7
            NcpStrict.Off -> {}
        }
        if (p.horizontalCollision && (ncpStrict.value == NcpStrict.New || ncpStrict.value == NcpStrict.Motion) && p.tickCount % 2 == 0) {
            e.y = -0.07840000152587923
        }
        if (!infDurability.value && ncpStrict.value != NcpStrict.Motion) return
        if (Movement.moving() || abs(e.x) >= 0.121 || abs(e.z) >= 0.121) return
        val a = Math.toRadians(4.5 * (p.tickCount % 80)).toFloat()
        e.x = sin(a.toDouble()) * 0.12
        e.z = cos(a.toDouble()) * 0.12
    }

    init {
        Movement.init()
        on<AfterSendPacketEvent> { e ->
            val pk = e.packet
            if (mode.value == Mode.FIRE_WORK && pk is ServerboundPlayerCommandPacket &&
                pk.action == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ) {
                useFirework(false)
            }
        }
        on<ReceivePacketEvent> { e ->
            when (mode.value) {
                Mode.FIRE_WORK -> {
                    if (e.packet is ClientboundPlayerPositionPacket) {
                        accel = 0f
                        fwYAccel = 0f
                        if (disableOnFlag.value) disableWith("modules.common.flag-disable")
                    }
                }
                Mode.BOOST -> {
                    val p = mc.player ?: return@on
                    val pk = e.packet
                    if (pk is ClientboundSetEntityDataPacket && pk.id() == p.id && EquipUtil.held(lift.value) && pk.packedItems().isNotEmpty()) {
                        e.cancel()
                    }
                }
                Mode.PACKET -> {
                    val pk = e.packet as? ClientboundSetEntityDataPacket ?: return@on
                    if (pk.id() == mc.player?.id && pk.packedItems().isNotEmpty()) e.cancel()
                }
                Mode.SILENT_FIRE_WORK -> {
                    val pk = e.packet
                    if (pk is ClientboundSoundPacket) {
                        val s = pk.sound.value()
                        if (s == SoundEvents.ARMOR_EQUIP_ELYTRA.value() ||
                            s == SoundEvents.ARMOR_EQUIP_NETHERITE.value() ||
                            s == SoundEvents.ARMOR_EQUIP_DIAMOND.value() ||
                            s == SoundEvents.ARMOR_EQUIP_GOLD.value() ||
                            s == SoundEvents.ARMOR_EQUIP_IRON.value() ||
                            s == SoundEvents.ARMOR_EQUIP_GENERIC.value() ||
                            s == SoundEvents.ARMOR_EQUIP_CHAIN.value() ||
                            s == SoundEvents.ARMOR_EQUIP_LEATHER.value()
                        ) {
                            e.cancel()
                        }
                    }
                }
                else -> {}
            }
        }
        on<ElytraTravelEvent> { e ->
            val p = mc.player ?: return@on
            when (mode.value) {
                Mode.FIRE_WORK -> {
                    if (e.pre) {
                        if (PlayerState.elytraTicks < 4) {
                            savedPitch = p.xRot
                            p.xRot = -45.0f
                        }
                    } else if (PlayerState.elytraTicks < 4) {
                        p.xRot = savedPitch
                    }
                }
                Mode.INFINITE -> {
                    if (e.pre) {
                        savedPitch = p.xRot
                        p.xRot = targetPitch
                    } else {
                        p.xRot = savedPitch
                    }
                }
                Mode.SILENT_FIRE_WORK -> {
                    if (e.pre) {
                        silentSavedYaw = p.yRot
                        silentSavedPitch = p.xRot
                        p.yRot = silentTargetYaw()
                        if (!pitchControl.value) p.xRot = 0.0f
                        p.xRot = silentTargetPitch()
                    } else {
                        p.yRot = silentSavedYaw
                        p.xRot = silentSavedPitch
                    }
                }
                else -> {}
            }
        }
        on<PreSyncEvent> { e ->
            when (mode.value) {
                Mode.FIRE_WORK -> fireworkPreSync()
                Mode.BOOST -> boostPreSync()
                Mode.CONTROL -> controlPreSync()
                Mode.INFINITE -> infinitePreSync()
                Mode.PACKET -> packetPreSync(e)
                else -> {}
            }
        }
        on<MoveEvent>(-20) { e ->
            when (mode.value) {
                Mode.FIRE_WORK -> fireworkMove(e)
                Mode.BOOST -> boostMove(e)
                Mode.CONTROL -> controlMove(e)
                Mode.PACKET -> packetMove(e)
                else -> {}
            }
        }
        on<PlayerUpdateEvent> { e ->
            when (mode.value) {
                Mode.FIRE_WORK -> fireworkUpdate()
                Mode.BOOST -> boostUpdate()
                Mode.INFINITE -> targetPitch = infinitePitch()
                Mode.SILENT_FIRE_WORK -> silentFireworkUpdate(e)
                else -> {}
            }
        }
        on<FireworkVelocityEvent> { e ->
            if (mode.value == Mode.SILENT_FIRE_WORK) silentFireworkVelocity(e)
        }
        on<SetPoseEvent> { e ->
            if (mode.value == Mode.SILENT_FIRE_WORK) {
                e.cancel()
                e.pose = Pose.STANDING
            }
        }
    }
}
