package dev.horizon.client.module.legit

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.combat.Found
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Scheduler
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.hud.Hud
import dev.horizon.client.mixin.legit.MouseHandlerAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items

/**
 * Refills the off-hand totem like a person would — opens the inventory screen 2–4
 * ticks after the pop, drifts the cursor towards a random totem, swaps it into the off-hand with the F-key click 2–4
 * ticks later and closes the screen 3–5 ticks after that. No settings.
 */
class LegitTotem : Module("legit-totem", Categories.LEGIT) {
    /** Progress in ticks, 0 = idle; never reset on enable/disable. */
    private var progress = 0

    /** [progress] at which the next step happens. */
    private var target = 0

    init {
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            val offTotem = p.offhandItem.`is`(Items.TOTEM_OF_UNDYING)
            val found = pickTotem() ?: return@on
            if (progress > 0) progress++
            if (!offTotem && progress == 0) {
                target = Rng.int(2, 4)
                progress = 1
            }
            if (mc.gui.screen() != null && !offTotem) moveCursorTowards(found.slot)
            if (progress < target) return@on
            if (mc.gui.screen() == null) {
                mc.tutorial.onOpenInventory()
                mc.gui.setScreen(InventoryScreen(p))
                target += Rng.int(2, 4)
                PlayerState.releaseMovementKeys()
                return@on
            }
            // Whatever screen is open gets the click (in a chest the slot numbers differ; on purpose).
            Inventory.click(found.slot, 40, ContainerInput.SWAP, false)
            Scheduler.ticks(Rng.int(2, 4)) { closeAll() }
            progress = 0
        }
    }

    /**
     * A uniformly random totem among inventory indices 36 down to 0 (Kotlin's unseeded random, re-rolled
     * every tick), as a container slot id: the hotbar becomes 36..44, index 36 (boots) stays 36.
     */
    private fun pickTotem(): Found? {
        val inv = mc.player?.inventory ?: return null
        val list = ArrayList<Found>()
        for (i in 36 downTo 0) {
            val st = inv.getItem(i)
            if (st.`is`(Items.TOTEM_OF_UNDYING)) list += Found(if (i < 9) i + 36 else i, st)
        }
        return if (list.isEmpty()) null else list.random()
    }

    /**
     * Warps the cursor 60 % of the way to an approximation of the slot's position (20 px grid from the
     * centre), in framebuffer pixels (GUI coordinates × GUI scale), and tells the mouse handler.
     */
    private fun moveCursorTowards(slot: Int) {
        val window = mc.window
        val baseX = window.guiScaledWidth / 2.0f - 80.0f
        val baseY = window.guiScaledHeight / 2.0f
        val tx = baseX + slot % 9 * 20
        val ty = baseY + (Math.floorDiv(slot, 9) - 1) * 20
        val nx = Hud.mouseX + (tx - Hud.mouseX) * 0.6
        val ny = Hud.mouseY + (ty - Hud.mouseY) * 0.6
        val s = window.guiScale
        // 26.2's grabOrReleaseMouse(window, GLFW_CURSOR_NORMAL, x, y) is releaseMouse(window, x, y) in 26.3.
        InputConstants.releaseMouse(window, nx * s, ny * s)
        val mouse = mc.mouseHandler as MouseHandlerAccessor
        mouse.horizon_setXpos((nx * s).toInt().toDouble())
        mouse.horizon_setYpos((ny * s).toInt().toDouble())
    }

    /** Closes whatever is open (client side first, so the close packet carries id 0) and re-syncs the keys. */
    private fun closeAll() {
        val p = mc.player ?: return
        p.clientSideCloseContainer()
        p.closeContainer()
        mc.gui.setScreen(null)
        PlayerState.resyncMovementKeys()
    }
}
