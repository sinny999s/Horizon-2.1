package dev.horizon.client.module.basic

import dev.horizon.client.combat.Found
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.combat.Slots
import dev.horizon.client.combat.disableWith
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.item.Items

/**
 * After 18 ticks of gliding it puts a chestplate on from the hotbar, a tick later
 * the elytra again, and two ticks after that starts gliding anew.
 */
class InfiniteElytra : Module("infinite-elytra", Categories.BASIC) {
    init {
        on<PlayerUpdateEvent>(-10) {
            val p = mc.player ?: return@on
            if (!p.isFallFlying || PlayerState.elytraTicks < 18) return@on
            val chestplate = Inventory.hotbarFind { it.item in chestplates }
            if (chestplate == null) disableWith("no-armor-hotbar")
            Slots.useHotbar(chestplate?.slot ?: -1)
            Scheduler.ticks(0) { elytraBack(chestplate) }
            PlayerState.elytraTicks = 0
        }
    }

    /** Checks the chestplate search again (not the elytra one), on purpose. */
    private fun elytraBack(chestplate: Found?) {
        val elytra = Inventory.hotbarFind(Items.ELYTRA)
        if (chestplate == null) disableWith("no-elytra-hotbar")
        Slots.useHotbar(elytra?.slot ?: -1)
        Scheduler.ticks(1) {
            val p = mc.player ?: return@ticks
            Packets.send(ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
            p.startFallFlying()
        }
    }

    private companion object {
        val chestplates = setOf(Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE)
    }
}
