package dev.horizon.client.module.misc

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.ItemSwitch
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.combat.containerClick
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.gui.screens.inventory.MerchantScreen
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.phys.EntityHitResult

/**
 * Looks at and opens the nearest villager within 4 blocks that wasn't traded with in
 * the last 160 ticks, then in its trade screen takes every trade that gives a [buyItem] or costs a [sellItem] (with
 * enough of the first cost item), one per [tradeDelay] ticks, emptying the input slots between trades, and closes it.
 */
class AutoTrader : Module("auto-trader", Categories.MISC) {
    val buy = bool("buy", true)
    val buyItem = items("buy-item")
    val sell = bool("sell", false)
    val sellItem = items("sell-item")
    val openDelay = int("open-delay", 12, 0..40)
    val tradeDelay = int("trade-delay", 3, 0..40)

    private var openTicks = 0
    private var tradeTicks = 0
    private var villagerId = 0

    /** Villager id → player tick of the last finished trade screen. */
    private val traded = LinkedHashMap<Int, Int>()

    init {
        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (openTicks > 0) openTicks--
            if (tradeTicks > 0) {
                tradeTicks--
                return@on
            }
            traded.entries.removeIf { player.tickCount - it.value > 160 }
            val screen = mc.gui.screen()
            if (screen is MerchantScreen) {
                val menu = screen.menu
                val offers = menu.offers
                for (i in 0 until offers.size) {
                    if (wanted(offers[i])) {
                        menu.tryMoveItems(i)
                        menu.setSelectionHint(i)
                        Packets.send(ServerboundSelectTradePacket(i))
                        quickMove(2)
                        return@on
                    }
                    if (!menu.getSlot(0).item.isEmpty) {
                        quickMove(0)
                        return@on
                    }
                    if (!menu.getSlot(1).item.isEmpty) {
                        quickMove(1)
                        return@on
                    }
                    traded[villagerId] = player.tickCount
                }
                player.closeContainer()
            } else if (openTicks <= 0 && screen !== dev.horizon.client.gui.modern.ModernGui) {
                val villager = level.entitiesForRendering()
                    .filter { it is Villager && player.distanceToSqr(it) < 16.0 && !traded.containsKey(it.id) }
                    .minByOrNull { it.distanceTo(player) } ?: return@on
                val to = villager.eyePosition.add(Math.random() * 0.2, 0.0, Math.random() * 0.2)
                RotationManager.queue(RotationRequest(Rotation.lookAt(to), 150) { open(villager) })
            }
        }
    }

    private fun quickMove(slot: Int) {
        ItemSwitch.switching = true
        containerClick(slot, 0, ContainerInput.QUICK_MOVE)
        ItemSwitch.switching = false
        tradeTicks = tradeDelay.value
    }

    /** Called once the rotation is sent. */
    private fun open(villager: Entity) {
        val player = mc.player ?: return
        mc.gameMode?.interact(player, villager, EntityHitResult(villager, villager.boundingBox.center), InteractionHand.MAIN_HAND)
        villagerId = villager.id
        openTicks = openDelay.value
    }

    /** A wanted result we can pay for, or a sold item we have enough of. */
    private fun wanted(offer: MerchantOffer): Boolean {
        for (item in buyItem.value) {
            val enough = offer.costA.count <= Inventory.count(offer.costA.item)
            if (offer.result.item == item && !offer.isOutOfStock && buy.value && enough) return true
        }
        for (item in sellItem.value) {
            val enough = offer.costA.count <= Inventory.count(offer.costA.item)
            if (offer.costA.item == item && !offer.isOutOfStock && sell.value && enough) return true
        }
        return false
    }
}
