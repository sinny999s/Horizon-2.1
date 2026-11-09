package dev.horizon.client.module.rage

import dev.horizon.client.combat.Packets
import dev.horizon.client.event.ClearClickMapEvent
import dev.horizon.client.event.KeyboardInputEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ShouldStopSprintingEvent
import dev.horizon.client.event.SlipperinessEvent
import dev.horizon.client.event.SlowDownEvent
import dev.horizon.client.event.StepOnSlimeEvent
import dev.horizon.client.event.SweetBerryBushCollisionEvent
import dev.horizon.client.event.VelocityMultiplierEvent
import dev.horizon.client.mixin.ClientInputAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.basic.Sprint
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec2

/**
 * Undoes the item-use slowdown (the input ×5 before vanilla's ×0.2) for the selected item
 * kinds, with each mode's server trick (NCP re-sends the slot, GRIM uses the offhand or re-sends the slot, GRIM_2/3
 * click the crafting slot, GRIM_V3 only every other tick); MATRIX scales the input itself. Also skips the
 * soul-sand/honey/slime/ice/berry-bush slowdowns and the sneak/crawl one.
 */
class NoSlow : Module("no-slow", Categories.RAGE) {
    enum class Mode { VANILLA, NCP, GRIM, MATRIX, GRIM_V3, GRIM_2, GRIM_3 }

    val mode = enum("mode", Mode.VANILLA)
    val limitPackets = bool("limit-packets", false) { mode.value == Mode.GRIM }
    val delay = int("delay", 3, 1..10) { mode.value == Mode.GRIM_2 }
    val mainHand = bool("main-hand", true)
    val selection = group("selection", GroupMode.BOOLS)
    val food = bool("food", true, parent = selection.value)
    val projectiles = bool("projectiles", true, parent = selection.value)
    val shield = bool("shield", true, parent = selection.value)
    val soulSand = bool("soul-sand", true, parent = selection.value)
    val honey = bool("honey", true, parent = selection.value)
    val slime = bool("slime", true, parent = selection.value)
    val ice = bool("ice", true, parent = selection.value)
    val sweetBerryBush = bool("sweet-berry-bush", true, parent = selection.value)
    val sneak = bool("sneak", false, parent = selection.value)
    val crawl = bool("crawl", false, parent = selection.value)

    /** Flips every tick, last tick's flip, force the flip on next tick, clicking, sprint. */
    private var flip = false
    private var lastFlip = false
    private var forceFlip = false
    private var clicking = false
    private var savedSprint = false

    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            lastFlip = flip
            flip = !flip
            if (forceFlip) {
                flip = true
                forceFlip = false
            }
            if (!p.isUsingItem || p.isHandsBusy || p.isFallFlying) return@on
            when (mode.value) {
                Mode.NCP -> Packets.send(ServerboundSetCarriedItemPacket(p.inventory.selectedSlot))
                Mode.GRIM -> if (p.usedItemHand == InteractionHand.OFF_HAND) {
                    Packets.send(ServerboundSetCarriedItemPacket(p.inventory.selectedSlot % 8 + 1))
                    Packets.send(ServerboundSetCarriedItemPacket(p.inventory.selectedSlot))
                } else if (mainHand.value && (p.ticksUsingItem <= 3 || p.tickCount % 2 == 0 || !limitPackets.value)) {
                    mc.gameMode?.useItem(p, InteractionHand.OFF_HAND)
                }
                else -> {}
            }
        }
        on<SlipperinessEvent> { e ->
            if (slime.value && e.block == Blocks.SLIME_BLOCK) e.cancel()
            if (ice.value && (e.block == Blocks.ICE || e.block == Blocks.PACKED_ICE || e.block == Blocks.BLUE_ICE || e.block == Blocks.FROSTED_ICE) &&
                !mc.options.keyJump.isDown) e.cancel()
        }
        on<VelocityMultiplierEvent> { e ->
            if (soulSand.value && e.block == Blocks.SOUL_SAND || honey.value && e.block == Blocks.HONEY_BLOCK) e.cancel()
        }
        on<StepOnSlimeEvent> { e -> if (slime.value) e.cancel() }
        on<ClearClickMapEvent> { e -> if (clicking) e.cancel() }
        on<KeyboardInputEvent> {
            val p = mc.player ?: return@on
            val input = p.input
            if (mode.value == Mode.MATRIX && p.isUsingItem && !p.isFallFlying) {
                (input as ClientInputAccessor).horizon_setMoveVector(Vec2(input.moveVector.x * 5, input.moveVector.y * 5))
                val diagonal = input.moveVector.y != 0.0f && input.moveVector.x != 0.0f
                val f = if (p.onGround()) (if (diagonal) 0.35f else 0.5f) else (if (diagonal) 0.47f else 0.67f)
                (input as ClientInputAccessor).horizon_setMoveVector(Vec2(input.moveVector.x * f, input.moveVector.y * f))
            } else if (noSlow() && p.isUsingItem && !p.isFallFlying) {
                (input as ClientInputAccessor).horizon_setMoveVector(Vec2(input.moveVector.x * 5, input.moveVector.y * 5))
            }
        }
        on<SlowDownEvent> { e ->
            val p = mc.player ?: return@on
            if (p.isVisuallyCrawling && crawl.value || sneak.value) e.cancel()
        }
        on<ShouldStopSprintingEvent> { e ->
            if (noSlow() && mc.player?.isUsingItem == true) e.cancel()
        }
        on<SweetBerryBushCollisionEvent> { e -> if (sweetBerryBush.value) e.cancel() }
    }

    /** Whether the item in use moves at full speed this tick (with the GRIM_2/GRIM_3 clicks as a side effect). */
    private fun noSlow(): Boolean {
        val p = mc.player ?: return false
        if (mode.value == Mode.MATRIX) return false
        val use = p.useItem
        if (!food.value && use.components.has(DataComponents.FOOD)) return false
        if (!shield.value && use.item == Items.SHIELD) return false
        if (!projectiles.value && (use.item == Items.CROSSBOW || use.item == Items.BOW || use.item == Items.TRIDENT)) return false
        if (!mainHand.value && p.usedItemHand == InteractionHand.MAIN_HAND) return false
        when (mode.value) {
            Mode.GRIM_2 -> {
                if (p.isUsingItem) {
                    if (p.ticksUsingItem >= delay.value) return true
                    if (p.ticksUsingItem <= 2) clickCraftingSlot()
                }
                return false
            }
            Mode.GRIM_3 -> {
                if (!p.isUsingItem) return false
                when (p.ticksUsingItem) {
                    1 -> {
                        savedSprint = p.isSprinting
                        p.isSprinting = false
                        Modules.get<Sprint>().skipOnce = true
                    }
                    2 -> {
                        clickCraftingSlot()
                        Modules.get<Sprint>().skipOnce = true
                    }
                    3 -> {
                        p.isSprinting = savedSprint
                        Modules.get<Sprint>().skipOnce = true
                    }
                    else -> return true
                }
                return false
            }
            Mode.GRIM_V3 -> {
                if (mc.options.keyShift.isDown) return false
                if (p.ticksUsingItem < 3) return false
                if (!p.onGround()) return !flip
                if (!mc.options.keyJump.isDown) return p.tickCount % 2 == 0
                if (lastFlip) return true
                forceFlip = true
                return false
            }
            else -> return !p.offhandItem.components.has(DataComponents.FOOD) && p.offhandItem.item != Items.SHIELD || mode.value != Mode.GRIM ||
                p.usedItemHand != InteractionHand.MAIN_HAND
        }
    }

    /** Under the [clicking] flag: a pickup click on crafting slot 1 that changes nothing here, then a close. */
    private fun clickCraftingSlot() {
        val p = mc.player ?: return
        clicking = true
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, 1, 0, ContainerInput.PICKUP, p)
        Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
        clicking = false
    }
}
