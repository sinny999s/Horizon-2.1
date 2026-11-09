package dev.horizon.client.module

import dev.horizon.client.combat.IgnoreMode
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.combat.Placer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.player.AutoMend
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.render.Theme
import dev.horizon.client.setting.GroupMode
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import java.awt.Color

/**
 * The block-placer base: the shared placing settings and the place-a-list loop. [priority] goes with every
 * rotation request; [showRange] shows the range settings (Surround and Blocker hide them).
 */
abstract class PlacerModule(id: String, subCategory: SubCategory, val priority: Int = 150, showRange: Boolean = true) :
    Module(id, subCategory) {
    val timing = enum("timing", Timing.Vanilla)
    val blocksPerAction = int("blocks-per-action", 6, 2..20) { timing.value == Timing.Instant }
    val delayPerAction = int("delay-per-action", 0, 0..10) { timing.value == Timing.Instant }
    val rotate = bool("rotate", true)
    val grimSilent = bool("grim-silent", false) { timing.value == Timing.Instant && rotate.value }
    val placeMode = enum("place-mode", PlaceMode.Normal)
    val interact = enum("interact", Interact.Strict)
    val switch = enum("switch", Switch2.Silent)
    val range = float("range", 4.0f, 2.0f..10.0f) { showRange }
    val wallRange = float("wall-range", 4.0f, 2.0f..10.0f) { showRange }
    val pause = group("pause", GroupMode.BOOLS)
    val mining = bool("mining", false, parent = pause.value)
    val eating = bool("eating", false, parent = pause.value)
    val inventory = bool("inventory", false, parent = pause.value)
    val blink = bool("blink", false, parent = pause.value)
    val render = enum("render", Render.BOTH)
    val animation = enum("animation", Animation.Fade)
    val syncColors = bool("sync-colors", false)
    val fillColor = color("fill-color", Color(0xFFFFFFFF.toInt(), true)) { !syncColors.value }
    val outlineColor = color("outline-color", Color(0xFFFFFFFF.toInt(), true)) { !syncColors.value }

    /** Calls of [placeList] still to skip after an Instant batch (`delay-per-action`). */
    private var delay = 0

    /** The last successful place; an inventory screen opening within 250 ms of it is closed straight away. */
    private val lastPlace = MsTimer()

    /** The blocks this module places. */
    open fun blocks(): List<Block> = Placer.DEFAULT_BLOCKS

    /** The items [placeItems] places, for things that aren't blocks (item frames). */
    open fun items(): List<Item> = emptyList()

    /** Entities in the target block that don't stop a placement. */
    open val ignoreMode: IgnoreMode get() = IgnoreMode.ONLY_CRYSTALS

    /** Whether a crystal in the target block may be broken first. */
    open fun mayBreak(crystal: Entity) = !paused()

    init {
        // A neighbour that opens a screen (a chest, an ender chest) was clicked by mistake: close it unseen.
        on<ReceivePacketEvent> { e ->
            val p = e.packet
            if (p is ClientboundOpenScreenPacket && !lastPlace.passed(250)) {
                Packets.send(ServerboundContainerClosePacket(p.containerId))
                e.cancel()
            }
        }
    }

    /**
     * Places down [positions] in order. Vanilla timing places one block per call, Instant up to
     * `blocks-per-action`. [immediate] places at once instead of when the rotation has been sent.
     */
    fun placeList(positions: List<BlockPos>, immediate: Boolean, forceVanilla: Boolean = false, blocks: List<Block> = blocks(),
                  altSearch: Boolean = false): Boolean = placeItems(positions, immediate, forceVanilla, blocks.map { it.asItem() }, altSearch)

    /** [placeList] with the items given as they are. */
    fun placeItems(positions: List<BlockPos>, immediate: Boolean, forceVanilla: Boolean = false, items: List<Item> = items(),
                   altSearch: Boolean = false): Boolean {
        if (delay > 0) {
            delay--
            return false
        }
        if (positions.isEmpty()) return false
        var budget = blocksPerAction.value
        var placed = false
        for (pos in positions) {
            if (budget <= 0) return placed
            if (!placeOne(pos, items, immediate, forceVanilla, altSearch)) continue
            placed = true
            if (timing.value == Timing.Instant) {
                budget--
                delay = delayPerAction.value
            } else {
                return true
            }
        }
        return placed
    }

    private fun placeOne(pos: BlockPos, items: List<Item>, immediate: Boolean, forceVanilla: Boolean, altSearch: Boolean): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val found = (if (altSearch) Inventory.hotbarFind { it.item is BlockItem && it.item !in items }
        else Inventory.hotbarFind { it.item in items }) ?: return false
        if (paused()) return false
        val req = Placer.place(pos, found.slot, if (forceVanilla) Interact.Vanilla else interact.value, placeMode.value, ignoreMode,
            range.value, wallRange.value, priority, switch.value, emptyList(), grimSilent.value) ?: return false
        val crystal = level.getEntitiesOfClass(Entity::class.java, AABB(pos)).firstOrNull { it is EndCrystal }
        if (crystal != null) {
            if (!mayBreak(crystal)) return false
            Packets.send(ServerboundAttackPacket(crystal.id))
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
        }
        val instant = timing.value == Timing.Instant
        if (rotate.value) {
            if (instant) RotationManager.queueInstant(req) else RotationManager.queue(req)
        }
        if (!rotate.value || immediate) {
            val silent = rotate.value && grimSilent.value && instant
            if (silent) Packets.sendRotation(req.rotation.yaw, req.rotation.pitch)
            req.action()
            if (silent) Packets.sendRotation(player.yRot, player.xRot)
            req.action = {}
        }
        if (render.value != Render.NONE) {
            val sync = Theme.primary or (0xFF shl 24)
            PlaceBoxes.add(pos, if (syncColors.value) sync else outlineColor.value.rgb, if (syncColors.value) sync else fillColor.value.rgb,
                animation.value, render.value)
        }
        Modules.get<AutoMend>().placeTimer.reset()
        lastPlace.reset()
        return true
    }

    /** The pause settings, plus Speed Mine holding its tool. */
    fun paused(): Boolean {
        val player = mc.player ?: return true
        if (mining.value && mc.gameMode?.isDestroying == true) return true
        if (blink.value && Modules.get<Blink>().enabled) return true
        if (eating.value && player.isUsingItem) return true
        if (Modules.get<SpeedMine>().toolSwapInFlight.get()) return true
        return inventory.value && PlayerState.containerOpen
    }
}
