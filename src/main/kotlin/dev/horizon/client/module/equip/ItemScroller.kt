package dev.horizon.client.module.equip

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.event.ContainerClickEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.world.inventory.ContainerInput
import kotlin.random.Random

/**
 * In a screen, holding left shift and the left mouse button shift-clicks whatever is
 * under the cursor every [delay] (+0..9) ms. Ctrl+Shift+Q throws every stack of the hovered item, Ctrl+Alt+Q every
 * stack in the menu.
 */
class ItemScroller : Module("item-scroller", Categories.EQUIP) {
    val delay = int("delay", 75, 0..200)

    private var leftDown = false
    private var throwing = false
    private val timer = MsTimer()

    init {
        on<ContainerClickEvent> { e ->
            val p = mc.player ?: return@on
            val shift = Keys.down(InputConstants.KEY_LSHIFT) || Keys.down(InputConstants.KEY_RSHIFT)
            val ctrl = Keys.down(InputConstants.KEY_LCONTROL) || Keys.down(InputConstants.KEY_RCONTROL)
            val alt = Keys.down(InputConstants.KEY_LALT) || Keys.down(InputConstants.KEY_RALT)
            if (shift && ctrl && e.input == ContainerInput.THROW && !throwing) {
                val slots = p.containerMenu.slots
                val item = slots[e.slotId].item.item
                throwing = true
                for (i in 0 until slots.size) if (slots[i].item.item == item) EquipUtil.click(i, 1, ContainerInput.THROW)
                throwing = false
            }
            if (alt && ctrl && e.input == ContainerInput.THROW && !throwing) {
                throwing = true
                for (i in 0 until p.containerMenu.slots.size) EquipUtil.click(i, 1, ContainerInput.THROW)
                throwing = false
            }
        }
        on<InputEvent> { e ->
            if (e.device == InputEvent.Device.Mouse && e.key == InputConstants.MOUSE_BUTTON_LEFT) leftDown = e.action == InputEvent.Action.Press
        }
        on<Render3DEvent> {
            if (mc.gui.screen() == null || !leftDown || !Keys.down(InputConstants.KEY_LSHIFT)) return@on
            if (!timer.passed((delay.value + (Random.nextDouble() * 10).toInt()).toLong())) return@on
            // A left-button release, then a press with shift held: the screen sees a shift-click on the hovered slot.
            val handle = mc.window.handle()
            mc.mouseHandler.onButton(handle, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0), 0)
            mc.mouseHandler.onButton(handle, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT), 1)
            timer.reset()
        }
    }
}
