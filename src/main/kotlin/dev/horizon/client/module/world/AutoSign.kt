package dev.horizon.client.module.world

import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.ScreenEvent
import dev.horizon.client.mixin.world.AutoSignScreenAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.inventory.SignEditScreen
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Instead of opening a (standing or wall) sign's edit screen, sends the four lines at once;
 * with glowing on it then uses a glow ink sac on a front side that isn't glowing yet.
 */
class AutoSign : Module("auto-sign", Categories.WORLD) {
    val line1 = string("line1", "<player>")
    val line2 = string("line2", "was here")
    val line3 = string("line3", "<------------->")
    val line4 = string("line4", "<date>")
    val dateFormat = string("date-format", "dd/MM/yyyy") { listOf(line1, line2, line3, line4).any { it.value.contains("<date>") } }
    val glowing = bool("glowing", false)

    init {
        on<ScreenEvent> { e ->
            val screen = e.screen as? SignEditScreen ?: return@on
            val acc = screen as AutoSignScreenAccessor
            val sign = acc.horizon_sign()
            e.cancel()
            Packets.send(ServerboundSignUpdatePacket(sign.blockPos,
                listOf(fill(line1.value), fill(line2.value), fill(line3.value), fill(line4.value)), acc.horizon_slot()))
            if (!glowing.value || sign.getText(SignTextSlot.FRONT).hasGlowingText()) return@on

            val player = mc.player ?: return@on
            val ink = Inventory.hotbarFind(Items.GLOW_INK_SAC)
            val offhand = player.offhandItem.item == Items.GLOW_INK_SAC
            val sneaking = player.isShiftKeyDown
            if (ink == null && !offhand) return@on
            Inventory.saveAndSelect(ink?.slot ?: Inventory.selected)
            if (sneaking) PlayerState.setSneak(false)
            val hand = if (offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            mc.gameMode?.useItemOn(player, hand,
                BlockHitResult(Vec3.atCenterOf(sign.blockPos).add(0.0, 0.5, 0.0), Direction.UP, sign.blockPos, false))
            if (sneaking) PlayerState.setSneak(true)
            // No swing packet here; 26.3 has none.
            Inventory.restore()
        }
    }

    /** <player> → the account name, <date> → today in date-format (dd/MM/yyyy when the format is bad). */
    private fun fill(line: String): String {
        var date = "dd/MM/yyyy"
        try {
            date = SimpleDateFormat(dateFormat.value).format(Date())
        } catch (_: Exception) {
            moduleMessage(name.trim(), ChatFormatting.RED.toString() + "Your date format is wrong!")
        }
        return line.replace("<player>", mc.user.name).replace("<date>", date)
    }
}
