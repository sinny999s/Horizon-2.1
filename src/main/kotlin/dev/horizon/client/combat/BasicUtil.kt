package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.basic.HoleAnchor
import dev.horizon.client.module.esp.HoleEsp
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

private val mc get() = Minecraft.getInstance()

/** The basic area's helpers that keep state: started once at client init. */
object BasicUtil {
    fun init() {
        Rubberband.init()
    }
}

/** The inventory operations the basic modules use. */
object Slots {
    /** The hotbar, the whole inventory, or the hotbar and then the inventory. */
    fun find(mode: Switch, pred: (ItemStack) -> Boolean): Found? = when (mode) {
        Switch.NORMAL, Switch.SILENT -> Inventory.hotbarFind(pred)
        Switch.INVENTORY_NORMAL, Switch.INVENTORY_SILENT -> Inventory.find(pred)
        Switch.NORMAL_FULL, Switch.SILENT_FULL -> Inventory.hotbarFind(pred) ?: Inventory.find(pred)
    }

    /**
     * Run [action] holding [slot] (a hotbar index, or a container slot for the
     * inventory modes), switching back afterwards in the silent modes. Gui-move ignores the clicks this makes.
     */
    fun withSwitch(slot: Int, mode: Switch, action: () -> Unit) {
        val p = mc.player ?: return
        val guiMove = Modules.get<GuiMove>()
        guiMove.internalClick = true
        when (mode) {
            Switch.NORMAL -> {
                if (p.inventory.selectedSlot != slot) Inventory.selectHotbar(slot)
                action()
            }
            Switch.SILENT -> {
                val old = p.inventory.selectedSlot
                if (Inventory.serverSlot != slot) Inventory.selectHotbar(slot)
                action()
                if (Inventory.serverSlot != old) Inventory.selectHotbar(old)
            }
            Switch.INVENTORY_NORMAL -> {
                Inventory.swap(slot, p.inventory.selectedSlot, false)
                action()
            }
            Switch.INVENTORY_SILENT -> {
                Inventory.swap(slot, p.inventory.selectedSlot, false)
                action()
                Inventory.swap(slot, p.inventory.selectedSlot, false)
            }
            Switch.NORMAL_FULL -> withSwitch(slot, if (slot >= 9) Switch.INVENTORY_NORMAL else Switch.NORMAL, action)
            Switch.SILENT_FULL -> withSwitch(slot, if (slot >= 9) Switch.INVENTORY_SILENT else Switch.SILENT, action)
        }
        guiMove.internalClick = false
    }

    /**
     * Right-click with hotbar [slot], then select the old slot again. No swing packet is sent; 26.3 has none.
     */
    fun useHotbar(slot: Int) {
        val p = mc.player ?: return
        val old = p.inventory.selectedSlot
        if (slot == -1) return
        Inventory.selectHotbar(slot)
        mc.gameMode!!.useItem(p, InteractionHand.MAIN_HAND)
        Inventory.selectHotbar(old)
    }

    /** A click on container slot [slot] of the open menu, then optionally a close packet. */
    fun click(slot: Int, button: Int = 0, input: ContainerInput = ContainerInput.PICKUP, close: Boolean = false) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, button, input, p)
        if (close) Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
    }

    /**
     * -2 when an elytra with damage under 430 is worn, else the container slot of the first such
     * elytra in inventory indices 0..35 (hotbar as 36..44), or -1.
     */
    fun elytra(): Int {
        val inv = mc.player!!.inventory
        for (i in 39 downTo 36) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return -2
        }
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (s.`is`(Items.ELYTRA) && s.damageValue < 430) return if (i < 9) i + 36 else i
        }
        return -1
    }
}

/**
 * Turn the module off and say why in chat. The off switch has no "already off" check, so a second call notifies again
 * (on purpose).
 */
fun Module.disableWith(message: String) {
    enabledSetting.value = false
    unplug()
    onDisable()
    Notifications.post(name, "was disabled", 1f, NotificationType.DISABLED)
    moduleMessage(name.trim(), message)
}

/**
 * When the server last corrected our position (a "rubberband"), and when we last used a
 * chorus fruit or anyone threw a pearl (corrections within 10 s after those don't count).
 */
object Rubberband {
    @Volatile
    private var lastCorrection = -1L

    @Volatile
    private var lastChorusOrPearl = -1L

    /** Milliseconds since the last rubberband. */
    fun sinceLast(): Long = System.currentTimeMillis() - lastCorrection

    fun init() {
        EventBus.on<SendPacketEvent>(this) { e ->
            if (e.packet !is ServerboundUseItemPacket) return@on
            if (mc.player?.useItem?.`is`(Items.CHORUS_FRUIT) == true) lastChorusOrPearl = System.currentTimeMillis()
        }
        EventBus.on<ReceivePacketEvent>(this) { e ->
            when (val packet = e.packet) {
                is ClientboundPlayerPositionPacket -> {
                    val p = mc.player ?: return@on
                    if (System.currentTimeMillis() - lastChorusOrPearl - 10000L > 0 && p.tickCount > 20) {
                        lastCorrection = System.currentTimeMillis()
                    }
                }
                is ClientboundSoundPacket -> if (packet.sound.value() == SoundEvents.ENDER_PEARL_THROW) {
                    lastChorusOrPearl = System.currentTimeMillis()
                }
            }
        }
    }

    /** Called at `MultiPlayerGameMode.releaseUsingItem` HEAD. */
    fun stopUsing() {
        if (mc.player?.useItem?.`is`(Items.CHORUS_FRUIT) == true) lastChorusOrPearl = System.currentTimeMillis()
    }
}

