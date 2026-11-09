package dev.horizon.client.module

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.gui.modern.ModernGui
import dev.horizon.client.setting.Bind
import net.minecraft.client.gui.screens.Screen

/** The ClickGUI's bind and 3D line mode, and [opacity], the window's opacity. */
class ClickGuiModule : Module("UI", Categories.MISC, defaultBind = Bind(InputConstants.KEY_P)) {
    enum class LinesMode { OLD, NEW }

    val opacity = float("opacity", 0.78f, 0.35f..0.95f)
    val linesMode = enum("lines-mode", LinesMode.NEW)

    fun screen(): Screen = ModernGui
}
