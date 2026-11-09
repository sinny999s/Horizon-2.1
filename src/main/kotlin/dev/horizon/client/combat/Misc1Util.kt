package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.Switch
import dev.horizon.client.module.basic.GuiMove
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val mc get() = Minecraft.getInstance()

/**
 * Switch-and-act helpers: find the item, put it in
 * hand the way [Switch] says, run the action, and switch back for the silent modes.
 */
object ItemSwitch {
    /**
     * gui-move's flag, set for the duration of every switch and around auto-trader's clicks.
     */
    var switching: Boolean
        get() = Modules.get<GuiMove>().internalClick
        set(value) {
            Modules.get<GuiMove>().internalClick = value
        }

    /** False when the item isn't where [mode] looks. */
    fun use(item: Item, mode: Switch, action: () -> Unit): Boolean {
        val found = when (mode) {
            Switch.NORMAL, Switch.SILENT -> Inventory.hotbarFind(item)
            Switch.INVENTORY_NORMAL, Switch.INVENTORY_SILENT -> Inventory.find(item)
            Switch.SILENT_FULL, Switch.NORMAL_FULL -> Inventory.hotbarFind(item) ?: Inventory.find(item)
        } ?: return false
        run(found.slot, mode, action)
        return true
    }

    /** [slot] is a hotbar index for the hotbar modes, a container slot id for the inventory ones. */
    fun run(slot: Int, mode: Switch, action: () -> Unit) {
        val p = mc.player ?: return
        switching = true
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
                swap(slot)
                action()
            }
            Switch.INVENTORY_SILENT -> {
                swap(slot)
                action()
                swap(slot)
            }
            Switch.NORMAL_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_NORMAL else Switch.NORMAL, action)
            Switch.SILENT_FULL -> run(slot, if (slot >= 9) Switch.INVENTORY_SILENT else Switch.SILENT, action)
        }
        switching = false
    }

    private fun swap(slot: Int) {
        val p = mc.player ?: return
        mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, p.inventory.selectedSlot, ContainerInput.SWAP, p)
    }
}

/** A click on [slot] of the open container (button 0, PICKUP by default), then optionally a close. */
fun containerClick(slot: Int, button: Int = 0, input: ContainerInput = ContainerInput.PICKUP, close: Boolean = false) {
    val p = mc.player ?: return
    mc.gameMode?.handleContainerInput(p.containerMenu.containerId, slot, button, input, p)
    if (close) Packets.send(ServerboundContainerClosePacket(p.containerMenu.containerId))
}

/**
 * "> [Name] text" in chat, at once on the main thread (queued otherwise). Unlike
 * [moduleMessage] it doesn't wait for the next frame, which matters right before a disconnect.
 */
fun moduleChat(module: Module, text: String) {
    val msg = Component.nullToEmpty("§a> §7[§6${module.name.trim()}§7] $text")
    if (mc.isSameThread) mc.player?.sendSystemMessage(msg) else mc.executeIfPossible { mc.player?.sendSystemMessage(msg) }
}

/**
 * Set while a module sends its own chat lines, so team-communication leaves
 * them alone. TeamCommunication should read this one.
 */
val teamChatBypass = AtomicBoolean(false)

/** The block entities of every loaded chunk within render distance (chunk coords by truncation). */
fun blockEntitiesAround(): List<BlockEntity> {
    val p = mc.player ?: return emptyList()
    val level = mc.level ?: return emptyList()
    val rd = mc.options.renderDistance().get()
    val cx = p.x.toInt() / 16
    val cz = p.z.toInt() / 16
    val list = ArrayList<BlockEntity>()
    for (i in -rd..rd) for (j in -rd..rd) level.chunkSource.getChunkNow(cx + i, cz + j)?.let { list.addAll(it.blockEntities.values) }
    return list
}

/**
 * Pop tracker (counting part): totem pops per player name from entity event 35 (not our own), reset when
 * the player is seen dead, with a 1000 ms window against counting a corpse twice. Always on once started.
 */
object PopCounter {
    private val pops = ConcurrentHashMap<String, Int>()
    private val recentlyDead = ConcurrentHashMap<String, Long>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        EventBus.on<ReceivePacketEvent>(this, 10) { e ->
            val p = e.packet as? ClientboundEntityEventPacket ?: return@on
            if (p.eventId.toInt() != 35) return@on
            val entity = p.getEntity(mc.level ?: return@on) as? Player ?: return@on
            if (entity === mc.player) return@on
            pops.merge(entity.name.string, 1, Int::plus)
        }
        EventBus.on<PlayerUpdateEvent>(this, 10) {
            val level = mc.level ?: return@on
            val dead = level.players().filter {
                !(it.health > 0f && it.isAlive && !it.isDeadOrDying) && it !== mc.player && !recentlyDead.containsKey(it.name.string)
            }
            for (p in dead) {
                recentlyDead[p.name.string] = System.currentTimeMillis()
                pops.remove(p.name.string)
            }
            recentlyDead.entries.removeIf { System.currentTimeMillis() - it.value > 1000 }
        }
    }

    fun pops(name: String): Int = pops[name] ?: 0
}
