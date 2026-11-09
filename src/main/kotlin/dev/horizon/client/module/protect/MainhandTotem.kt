package dev.horizon.client.module.protect

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Selects a hotbar totem (for good) at low health, when a crystal that could kill us spawns, or when we pop. The packet
 * triggers run on the network thread, between client ticks.
 */
class MainhandTotem : Module("mainhand-totem", Categories.PROTECT) {
    val hp = float("hp", 16.0f, 0.0f..36.0f)
    val pauseWhileEating = bool("pause-while-eating", false)

    /** The PlayerUpdate path's 1 s gate (the packet path has none). */
    private val timer = MsTimer()

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (health() - hp.value > 0) return@on
            if (!timer.passed(1000)) return@on
            if (!p.isUsingItem) {
                trySwitch(totemSlot())
                timer.reset()
            }
        }
        on<ReceivePacketEvent> { e ->
            if (!PlayerState.safeToAct()) return@on
            val p = mc.player ?: return@on
            try {
                val pk = e.packet
                if (pk is ClientboundAddEntityPacket && pk.type == EntityTypes.END_CRYSTAL) {
                    val dmg = Explosions.damage(Vec3(pk.x, pk.y, pk.z), p, 3, false, emptyList(), emptyList())
                    if (health() - dmg - hp.value <= 0) trySwitch(totemSlot())
                }
                if (pk is ClientboundEntityEventPacket && pk.eventId.toInt() == 35 && pk.getEntity(p.level()) === p) {
                    trySwitch(totemSlot())
                }
                // The health before this packet is applied (on purpose).
                if (pk is ClientboundSetHealthPacket && health() - hp.value <= 0) trySwitch(totemSlot())
            } catch (ex: Exception) {
                println("Mainhand Totem Error: " + ex.localizedMessage)
            }
        }
    }

    private fun health(): Float {
        val p = mc.player ?: return 0f
        return p.health + p.absorptionAmount
    }

    /** The first hotbar totem, or −1 when there's none or it's already selected. */
    private fun totemSlot(): Int {
        val p = mc.player ?: return -1
        val f = Inventory.hotbarFind(Items.TOTEM_OF_UNDYING) ?: return -1
        return if (f.slot == p.inventory.selectedSlot) -1 else f.slot
    }

    /** Never takes the last totem while Offhand is on. */
    private fun trySwitch(slot: Int) {
        val p = mc.player ?: return
        if (slot == -1) return
        if (p.isUsingItem && pauseWhileEating.value) return
        if (Inventory.menuCount(Items.TOTEM_OF_UNDYING) <= 1 && Modules.get<Offhand>().enabled) return
        Inventory.selectHotbar(slot)
    }
}
