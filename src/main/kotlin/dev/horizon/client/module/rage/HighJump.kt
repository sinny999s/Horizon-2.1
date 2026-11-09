package dev.horizon.client.module.rage

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Movement
import dev.horizon.client.combat.MoveFixKind
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.InstantRequest
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.TickTimer
import dev.horizon.client.event.AfterElytraEvent
import dev.horizon.client.event.FireworkVelocityEvent
import dev.horizon.client.event.JumpEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PreElytraEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.ScreenEvent
import dev.horizon.client.event.SetPoseEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.equip.ElytraSwap
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.ValueLabels
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Pose
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * VANILLA replaces the jump's power and sprint boost with `vertical`/`horizontal`.
 * GRIM_SHULKER (every 5 ticks while moving): next to a closed shulker box, opens it (and hides the screen); standing on
 * the opening lid, takes `vertical` upwards and closes it. ELYTRA: looks straight up, jumps, and for `ticks` ticks swaps
 * an elytra on, starts gliding and fires a rocket (first time), then swaps it back; turns off a few ticks later.
 */
class HighJump : Module("high-jump", Categories.RAGE) {
    enum class Mode { VANILLA, GRIM_SHULKER, ELYTRA }

    val mode = enum("mode", Mode.VANILLA)
    val vertical = float("vertical", 0.42f, 0.1f..20.0f)
    val horizontal = float("horizontal", 0.0f, 0.0f..5.0f).also { ValueLabels.register("horizontal", 0f, "copy-from-player") }
    val minimizeClicks = bool("minimize-clicks", false) { mode.value == Mode.ELYTRA }
    val offHandFirework = bool("offHand-firework", false) { mode.value == Mode.ELYTRA }
    val altFireworkSwap = bool("alt-firework-swap", false) { mode.value == Mode.ELYTRA }
    val ticks = int("ticks", 2, 1..15) { mode.value == Mode.ELYTRA }

    /** Elytra steps done; swallow the next screen (the shulker's); pitch saved around travel. */
    private var steps = 0
    @Volatile
    private var hideScreen = false
    private var savedPitch = 0f

    /** Four ticks between elytra steps. */
    private val stepTimer = TickTimer()

    init {
        on<ScreenEvent> { e ->
            if (hideScreen) {
                e.cancel()
                hideScreen = false
            }
        }
        on<JumpEvent> { e ->
            if (mode.value != Mode.VANILLA) return@on
            e.cancel()
            e.y = vertical.value
            e.xz = horizontal.value
        }
        on<PlayerUpdateEvent>(-10) { tick() }
        on<FireworkVelocityEvent> { e ->
            if (mode.value != Mode.ELYTRA) return@on
            val p = mc.player ?: return@on
            e.cancel()
            val v = p.deltaMovement
            e.velocity = p.deltaMovement.add(-v.x * 0.5, 0.1 + (1.5 - v.y) * 0.5, -v.z * 0.5)
        }
        on<PreElytraEvent> {
            if (mode.value != Mode.ELYTRA) return@on
            val p = mc.player ?: return@on
            savedPitch = p.xRot
            p.xRot = -90.0f
        }
        on<AfterElytraEvent> {
            if (mode.value == Mode.ELYTRA) mc.player?.xRot = savedPitch
        }
        on<SetPoseEvent> { e ->
            if (mode.value != Mode.ELYTRA) return@on
            e.cancel()
            e.pose = Pose.STANDING
        }
        on<ReceivePacketEvent> { e ->
            if (mode.value != Mode.ELYTRA) return@on
            val pk = e.packet as? ClientboundSoundPacket ?: return@on
            val s = pk.sound
            if (s == SoundEvents.ARMOR_EQUIP_ELYTRA || s == SoundEvents.ARMOR_EQUIP_NETHERITE || s == SoundEvents.ARMOR_EQUIP_DIAMOND ||
                s == SoundEvents.ARMOR_EQUIP_GOLD || s == SoundEvents.ARMOR_EQUIP_IRON || s == SoundEvents.ARMOR_EQUIP_GENERIC ||
                s == SoundEvents.ARMOR_EQUIP_CHAIN || s == SoundEvents.ARMOR_EQUIP_LEATHER) e.cancel()
        }
    }

    override fun onEnable() {
        steps = 0
    }

    private fun tick() {
        val p = mc.player ?: return
        if (p.abilities.flying || p.isFallFlying) return
        if (mode.value == Mode.GRIM_SHULKER && p.tickCount % 5 == 0 && Movement.moving()) shulkers()
        if (mode.value == Mode.ELYTRA) elytra()
    }

    private fun shulkers() {
        val p = mc.player ?: return
        for (box in ServerInfo.blockEntities.filterIsInstance<ShulkerBoxBlockEntity>()) {
            val closed = box.animationStatus != ShulkerBoxBlockEntity.AnimationStatus.OPENING &&
                box.animationStatus != ShulkerBoxBlockEntity.AnimationStatus.OPENED
            val lid = AABB(box.blockPos).inflate(0.5, 1.0, 0.5)
            val near = AABB(box.blockPos).inflate(1.5, 1.0, 1.5)
            World3D.outline(lid, Color.WHITE.rgb)
            if (!p.boundingBox.intersects(near)) continue
            if (closed && p.boundingBox.intersects(near)) {
                val face = closestFace(box.blockPos) ?: continue
                RotationManager.queue(InstantRequest(Rotation.lookAt(face.second), 150) {
                    val pl = mc.player ?: return@InstantRequest
                    mc.gameMode?.useItemOn(pl, InteractionHand.MAIN_HAND, BlockHitResult(face.second, face.first, box.blockPos, false))
                    pl.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                    hideScreen = true
                })
            } else if (p.boundingBox.intersects(lid)) {
                val m = if (horizontal.value == 0.0f) doubleArrayOf(p.deltaMovement.x, p.deltaMovement.z) else Movement.strafe(horizontal.value)
                p.setDeltaMovement(m[0], vertical.value.toDouble(), m[1])
                Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
            }
        }
    }

    /** The face of [pos] whose centre (jittered ±0.1 along the face) is nearest the eyes. */
    private fun closestFace(pos: BlockPos): Pair<Direction, Vec3>? {
        val p = mc.player ?: return null
        var best = 999.0f
        var face = Direction.UP
        var point: Vec3? = null
        for (d in Direction.values()) {
            val x = d.unitVec3i.x * 0.5
            val y = d.unitVec3i.y * 0.5
            val z = d.unitVec3i.z * 0.5
            val v = Vec3.atCenterOf(pos).add(
                if (Math.abs(x) == 0.5) x else Rng.double(-0.1, 0.1),
                if (Math.abs(y) == 0.5) y else Rng.double(-0.1, 0.1),
                if (Math.abs(z) == 0.5) z else Rng.double(-0.1, 0.1),
            )
            val dist = v.distanceToSqr(p.eyePosition).toFloat()
            if (best > dist) {
                face = d
                point = v
                best = dist
            }
        }
        return point?.let { face to it }
    }

    private fun elytra() {
        val p = mc.player ?: return
        val elytra = elytraSlot()
        if (elytra < 0 || Inventory.find(Items.FIREWORK_ROCKET) == null || p.isInWater || p.isInLava) {
            disable()
            return
        }
        RotationManager.rotate(Rotation(p.yRot, -90.0f, false, MoveFixKind.FORCE), 0)
        if (steps > ticks.value - 1) {
            if (steps++ > ticks.value + 5) {
                if (legitGuiMove()) PlayerState.resyncMovementKeys()
                disable()
            }
            return
        }
        if (p.onGround()) {
            if (!mc.options.keyJump.isDown) {
                mc.options.keyJump.isDown = true
                Scheduler.ticks(3) { if (!Keys.down(32)) mc.options.keyJump.isDown = false }
            }
            return
        }
        if (legitGuiMove() && Movement.moving()) {
            PlayerState.releaseMovementKeys()
            return
        }
        if (!stepTimer.passed(4)) return
        stepTimer.reset()
        val guiMove = Modules.get<GuiMove>()
        val elytraSwap = Modules.get<ElytraSwap>()
        if (elytra >= 36) {
            guiMove.internalClick = true
            val hotbar = elytra - 36
            Inventory.swap(6, hotbar, true)
            elytraSwap.skipFirework = true
            startGliding()
            if (steps == 0) firework()
            Inventory.swap(6, hotbar, true)
            guiMove.internalClick = false
        } else {
            guiMove.internalClick = true
            click(elytra, false)
            click(6, false)
            if (!minimizeClicks.value) click(elytra, true)
            elytraSwap.skipFirework = true
            startGliding()
            if (steps == 0) firework()
            if (!minimizeClicks.value) click(elytra, false)
            click(6, false)
            click(elytra, true)
            guiMove.internalClick = false
        }
        steps++
    }

    private fun startGliding() {
        val p = mc.player ?: return
        p.startFallFlying()
        Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
    }

    private fun legitGuiMove(): Boolean = Modules.get<GuiMove>().let { it.enabled && it.bypass.value == GuiMove.Bypass.LEGIT }

    /** A pickup click on container slot [slot], then optionally a close. */
    private fun click(slot: Int, close: Boolean) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, p)
        if (close) Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
    }

    /** −2 when wearing a usable elytra, else the container slot of the first one (−1 for none). */
    private fun elytraSlot(): Int {
        val p = mc.player ?: return -1
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)
        if (chest.`is`(Items.ELYTRA) && chest.damageValue < 430) return -2
        for (i in 0 until 36) {
            val s = p.inventory.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return if (i < 9) i + 36 else i
        }
        return -1
    }

    /** Elytra-target's rocket use, called with offHand-firework and alt-firework-swap. */
    private fun firework() {
        val p = mc.player ?: return
        val hotbar = Inventory.hotbarFind(Items.FIREWORK_ROCKET)
        val inv = Inventory.find(Items.FIREWORK_ROCKET)
        val hand = { if (p.offhandItem.`is`(Items.FIREWORK_ROCKET)) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND }
        if (offHandFirework.value && !Modules.get<Blink>().enabled) {
            if (p.offhandItem.`is`(Items.FIREWORK_ROCKET)) {
                mc.gameMode?.useItem(p, hand())
                return
            }
            if (inv == null) return
            if (altFireworkSwap.value) {
                click(inv.slot, false)
                click(45, false)
                click(inv.slot, false)
            } else {
                Inventory.swap(inv.slot, 40, false)
            }
            mc.gameMode?.useItem(p, hand())
            Inventory.swap(inv.slot, 40, true)
            return
        }
        if (hotbar != null) {
            Inventory.saveAndSelect(hotbar.slot)
        } else if (inv != null) {
            val s = p.inventory.selectedSlot % 8 + 1
            Inventory.swap(inv.slot, s, true)
            Inventory.saveAndSelect(s)
        } else if (!p.offhandItem.`is`(Items.FIREWORK_ROCKET)) {
            return
        }
        mc.gameMode?.useItem(p, hand())
        Inventory.restore()
        if (hotbar == null && inv != null) Inventory.swap(inv.slot, p.inventory.selectedSlot, true)
    }
}
