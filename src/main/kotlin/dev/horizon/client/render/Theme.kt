package dev.horizon.client.render

import dev.horizon.client.config.Storage
import java.awt.Color

/**
 * The live theme colours every GUI part reads. Switching theme fades from the previous theme's colours
 * to the new one over 1 s (OUT_EXPO); [update] runs once per world frame. At start-up the colours fade in from the
 * built-in default.
 */
object Theme {
    var primary = 0
    var secondary = 0
    var blur = 0
    var widgetBlur = 0
    var outline = 0
    var text = 0
    var inactiveText = 0
    var sliders = 0
    var inactiveModule = 0
    var module = 0
    var moduleOutline = 0
    var inactiveModuleOutline = 0

    /** The colour clipboard for Copy/Paste in the colour pickers. */
    var clipboard: Color = Color.WHITE

    private var previous = ThemeData.default()
    private val transition = Animation(Easing.OUT_EXPO, 1000)

    init {
        set(ThemeData.default())
    }

    private fun set(t: ThemeData) {
        primary = t.primaryColor
        secondary = t.secondaryColor
        blur = t.blurColor
        widgetBlur = t.widgetBlurColor
        outline = t.outlineColor
        text = t.textColor
        inactiveText = t.nonactiveTextColor
        sliders = t.slidersColor
        inactiveModule = t.nonActiveModuleColor
        module = t.moduleColor
        moduleOutline = t.moduleOutlineColor
        inactiveModuleOutline = t.nonActiveOutlineModuleColor
    }

    /** Make [t] current; the fade starts from the previous theme's stored colours. */
    fun select(t: ThemeData) {
        previous = Themes.current
        Themes.current = t
        Storage.shared.themeConfig = t.id
        transition.reset(0f)
    }

    /** Once per world frame. */
    fun update() {
        transition.update(1f)
        val p = previous
        val c = Themes.current
        val k = transition.value
        primary = Colors.lerp(p.primaryColor, c.primaryColor, k)
        secondary = Colors.lerp(p.secondaryColor, c.secondaryColor, k)
        blur = Colors.lerp(p.blurColor, c.blurColor, k)
        widgetBlur = Colors.lerp(p.widgetBlurColor, c.widgetBlurColor, k)
        outline = Colors.lerp(p.outlineColor, c.outlineColor, k)
        text = Colors.lerp(p.textColor, c.textColor, k)
        inactiveText = Colors.lerp(p.nonactiveTextColor, c.nonactiveTextColor, k)
        sliders = Colors.lerp(p.slidersColor, c.slidersColor, k)
        inactiveModule = Colors.lerp(p.nonActiveModuleColor, c.nonActiveModuleColor, k)
        module = Colors.lerp(p.moduleColor, c.moduleColor, k)
        moduleOutline = Colors.lerp(p.moduleOutlineColor, c.moduleOutlineColor, k)
        inactiveModuleOutline = Colors.lerp(p.nonActiveOutlineModuleColor, c.nonActiveOutlineModuleColor, k)
    }

    /** Primary towards secondary and back, only half-way (0..0.5), cycling with time. */
    fun gradient(offset: Int): Int {
        val t = ((System.currentTimeMillis() / 14.0 + offset) % 360).toInt()
        val tri = if (t < 180) t else 360 - t
        return Colors.lerp(primary, secondary, tri / 360f)
    }
}
