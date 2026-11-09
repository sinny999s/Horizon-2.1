package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.mixin.GameModeAccessor
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/** A found stack: [slot] is a hotbar index (hotbar searches) or a container slot id (whole-inventory searches). */
class Found(val slot: Int, val stack: ItemStack)

/**
 * Inventory helpers: hotbar switching that tracks the slot the server knows, number-key swaps
 * through the player's container, and searches.
 */
object Inventory {
    private val mc get() = Minecraft.getInstance()

    /** The hotbar slot last sent to the server. */
    var serverSlot = -1
        private set

    /** The slot [saveAndSelect] switched away from. */
    private var saved = -1

    fun init() {
        EventBus.on<SendPacketEvent>(this) { e ->
            val p = e.packet
            if (p is ServerboundSetCarriedItemPacket) serverSlot = p.slot
        }
    }

    val selected: Int get() = mc.player?.inventory?.selectedSlot ?: 0

    /** Select hotbar slot [s] client-side, and tell the server if it doesn't know it yet. */
    fun selectHotbar(s: Int) {
        val p = mc.player ?: return
        if (s !in 0..8 || s == p.inventory.selectedSlot) return
        p.inventory.selectedSlot = s
        (mc.gameMode as? GameModeAccessor)?.horizon_setCarriedIndex(s)
        if (serverSlot != s) Packets.send(ServerboundSetCarriedItemPacket(s))
    }

    fun saveAndSelect(s: Int) {
        saved = selected
        selectHotbar(s)
    }

    fun restore() {
        if (saved != -1) selectHotbar(saved)
        saved = -1
    }

    /** A number-key swap of inventory index [s] (hotbar 0..8 or main 9..35) with the selected slot. */
    fun swapWithSelected(s: Int) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, if (s < 9) s + 36 else s, p.inventory.selectedSlot, ContainerInput.SWAP, p)
    }

    /** A SWAP click on container slot [slotId] with hotbar [button] (40 = offhand), then optionally a close. */
    fun swap(slotId: Int, button: Int, close: Boolean) {
        val p = mc.player ?: return
        val id = p.containerMenu.containerId
        mc.gameMode?.handleContainerInput(id, slotId, button, ContainerInput.SWAP, p)
        if (close) Packets.send(ServerboundContainerClosePacket(id))
    }

    /** A click with any input on container slot [slotId], then optionally a close. */
    fun click(slotId: Int, button: Int, input: ContainerInput, close: Boolean) {
        val p = mc.player ?: return
        val id = p.containerMenu.containerId
        mc.gameMode?.handleContainerInput(id, slotId, button, input, p)
        if (close) Packets.send(ServerboundContainerClosePacket(id))
    }

    /** First hotbar slot (0..8) whose stack matches. */
    fun hotbarFind(pred: (ItemStack) -> Boolean): Found? {
        val inv = mc.player?.inventory ?: return null
        for (i in 0..8) if (pred(inv.getItem(i))) return Found(i, inv.getItem(i))
        return null
    }

    fun hotbarFind(item: Item): Found? = hotbarFind { it.`is`(item) }

    /** Inventory index 36 down to 0, as a container slot id (hotbar 0..8 become 36..44). */
    fun find(pred: (ItemStack) -> Boolean): Found? {
        val inv = mc.player?.inventory ?: return null
        for (i in 36 downTo 0) {
            val s = inv.getItem(i)
            if (pred(s)) return Found(if (i < 9) i + 36 else i, s)
        }
        return null
    }

    fun find(item: Item): Found? = find { it.`is`(item) }

    /** Stacks of [item] in the open menu (the inventory menu with no screen), plus the cursor. */
    fun menuCount(item: Item): Int {
        val menu = mc.player?.containerMenu ?: return 0
        var n = 0
        for (i in 0 until menu.slots.size) if (menu.slots[i].item.item == item) n++
        if (menu.carried.item == item) n++
        return n
    }

    /** Inventory indices 0..44 (main, armour, offhand). */
    fun count(item: Item): Int {
        val inv = mc.player?.inventory ?: return 0
        var n = 0
        for (i in 0 until minOf(45, inv.containerSize)) if (inv.getItem(i).`is`(item)) n += inv.getItem(i).count
        return n
    }
}
