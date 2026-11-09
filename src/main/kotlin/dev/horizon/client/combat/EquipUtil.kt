package dev.horizon.client.combat

import com.mojang.blaze3d.systems.RenderSystem
import dev.horizon.client.gui.Keys
import dev.horizon.client.setting.Bind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The click and use helpers the equip modules share. */
object EquipUtil {
    private val mc get() = Minecraft.getInstance()

    /** A click on [slot] of the open menu, then (with [close]) a close packet for that menu. */
    fun click(slot: Int, button: Int = 0, input: ContainerInput = ContainerInput.PICKUP, close: Boolean = false) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, button, input, p)
        if (close) Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
    }

    /** Vanilla main-hand item use (the packet carries the player's rotation). */
    fun use() {
        val p = mc.player ?: return
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
    }

    /**
     * Select hotbar [slot], use it ([count] times when it holds XP bottles), select back. No swing packet after the use;
     * 26.3 has none.
     */
    fun useInstant(slot: Int, count: Int) {
        val p = mc.player ?: return
        val prev = p.inventory.selectedSlot
        if (slot == -1) return
        Inventory.selectHotbar(slot)
        if (p.inventory.getItem(slot).`is`(Items.EXPERIENCE_BOTTLE)) repeat(count) { use() } else use()
        Inventory.selectHotbar(prev)
    }

    /** Swap menu slot [slot] into the selected hotbar slot, use, swap back (no swing packet in 26.3). */
    fun useViaSwap(slot: Int) {
        if (slot == -1) return
        val p = mc.player ?: return
        click(slot, p.inventory.selectedSlot, ContainerInput.SWAP)
        use()
        click(slot, p.inventory.selectedSlot, ContainerInput.SWAP)
    }

    /** Is the bind held (never off the render thread, with no key, or with chat open). */
    fun held(b: Bind): Boolean {
        if (!b.mouse) return Keys.down(b.key)
        if (!RenderSystem.isOnRenderThread() || b.key == -1 || mc.gui.screen() is ChatScreen) return false
        return b.key in Keys.heldMouse
    }

    /** Durability left in percent (NaN for items without durability). */
    fun durabilityPercent(s: ItemStack): Float = (s.maxDamage - s.damageValue).toFloat() / s.maxDamage * 100f
}
