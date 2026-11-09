package dev.horizon.client.module.protect

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.combat.RotationRequest
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents

/**
 * While a bow is drawn, moves a strength or swiftness arrow we lack the effect of to the
 * front, looks straight up and lets go after 4 ticks, so the arrow lands on us.
 */
class Quiver : Module("Quiver", Categories.PROTECT) {
    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (p.mainHandItem.item != Items.BOW) return@on
            if (p.ticksUsingItem < 4) return@on
            if (!prepareArrow()) return@on
            // The yaw is the player's pitch, on purpose (straight up, so it doesn't change the shot).
            RotationManager.queue(RotationRequest(Rotation(p.xRot, -90.0f), 0) { release() })
        }
    }

    /** Swap the first wanted arrow with the frontmost arrow (inventory indices used as menu slots, on purpose). */
    private fun prepareArrow(): Boolean {
        val p = mc.player ?: return false
        val arrows = ArrayList<Pair<Int, PotionContents>>()
        for (i in 0..35) {
            val s = p.inventory.getItem(i)
            if (s.item is ArrowItem) arrows += i to s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
        }
        if (arrows.isEmpty()) return false
        val first = arrows.minBy { it.first }.first
        for ((slot, contents) in arrows) {
            if (wanted(contents)) {
                if (slot != first) {
                    Inventory.click(slot, 0, ContainerInput.PICKUP, false)
                    Inventory.click(first, 0, ContainerInput.PICKUP, false)
                    Inventory.click(slot, 0, ContainerInput.PICKUP, true)
                }
                return true
            }
        }
        return false
    }

    /** The arrow's first effect is strength or speed, and we don't have it. */
    private fun wanted(contents: PotionContents): Boolean {
        val p = mc.player ?: return false
        val e = contents.allEffects.firstOrNull()?.effect
        if (e == MobEffects.STRENGTH && !p.hasEffect(MobEffects.STRENGTH)) return true
        if (e == MobEffects.SPEED && !p.hasEffect(MobEffects.SPEED)) return true
        return false
    }

    /** At PostSync while still rotated: release the bow without going through the game mode. */
    private fun release() {
        val p = mc.player ?: return
        Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, p.direction))
        p.releaseUsingItem()
    }
}
