package dev.horizon.client.module

import dev.horizon.client.config.Macros
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.widgets.KeystrokesWidget
import net.minecraft.client.Minecraft

/**
 * Key and mouse binds (the original's `cb.y`): the UI module's bind opens the ClickGUI (with no screen open); any other
 * module bound to the input is toggled with no screen open, or, for a "hold" bind, held on while the input is down in
 * any screen. The original compared only the code; here the device must match too, as 26.3's key and button codes overlap.
 */
object Binds {
    private const val RELEASE = 0
    private const val PRESS = 1

    fun onKey(key: Int, action: Int) = onInput(key, action, false)

    fun onMouse(button: Int, action: Int) {
        if (action == PRESS) Keys.heldMouse.add(button) else if (action == RELEASE) Keys.heldMouse.clear()
        KeystrokesWidget.onPhysicalMouse(button, action)
        onInput(button, action, true)
    }

    private fun onInput(key: Int, action: Int, mouse: Boolean) {
        if (key == -1 || (action != PRESS && action != RELEASE)) return
        val mc = Minecraft.getInstance()
        // Not in the original: Horizon's Lunar binds (spray, emote) and the Lunar menu's bind capture.
        if (dev.horizon.client.lunar.LunarSettings.input(key, mouse, action == PRESS)) return
        Macros.input(key, mouse, action == PRESS)
        val ui = Modules.get<ClickGuiModule>()
        if (action == PRESS && ui.bind.key == key && ui.bind.mouse == mouse && mc.gui.screen() == null) mc.gui.setScreen(ui.screen())
        for (m in Modules.all.filter { it !== ui && it.bind.key == key && it.bind.mouse == mouse }) {
            if (!m.bind.hold) {
                if (action == PRESS && mc.gui.screen() == null) m.toggle()
            } else if (action == PRESS) {
                m.enable()
            } else {
                m.disable()
            }
        }
    }
}
