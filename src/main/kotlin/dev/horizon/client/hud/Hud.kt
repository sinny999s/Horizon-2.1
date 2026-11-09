package dev.horizon.client.hud

import dev.horizon.client.hud.widgets.ArmorWidget
import dev.horizon.client.hud.widgets.ArrayListWidget
import dev.horizon.client.hud.widgets.SessionStatsWidget
import dev.horizon.client.hud.widgets.HypixelStatsWidget
import dev.horizon.client.hud.widgets.ChestCounterWidget
import dev.horizon.client.hud.widgets.CooldownsWidget
import dev.horizon.client.hud.widgets.CoordWidget
import dev.horizon.client.hud.widgets.Crosshair
import dev.horizon.client.hud.widgets.CrystalSpeedWidget
import dev.horizon.client.hud.widgets.FlightWidget
import dev.horizon.client.hud.widgets.FpsWidget
import dev.horizon.client.hud.widgets.InventoryWidget
import dev.horizon.client.hud.widgets.KeybindsWidget
import dev.horizon.client.hud.widgets.KeystrokesWidget
import dev.horizon.client.hud.widgets.MacroWidget
import dev.horizon.client.hud.widgets.MediaPlayerWidget
import dev.horizon.client.hud.widgets.NotificationsWidget
import dev.horizon.client.hud.widgets.PhaseWidget
import dev.horizon.client.hud.widgets.PingWidget
import dev.horizon.client.hud.widgets.PlayerListWidget
import dev.horizon.client.hud.widgets.PotionsWidget
import dev.horizon.client.hud.widgets.ScoreboardWidget
import dev.horizon.client.hud.widgets.PvpResourcesWidget
import dev.horizon.client.hud.widgets.SpeedWidget
import dev.horizon.client.hud.widgets.StaffWidget
import dev.horizon.client.hud.widgets.TargetInfo
import dev.horizon.client.hud.widgets.TpsWidget
import dev.horizon.client.hud.widgets.WatermarkWidget
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.VectorIcon
import dev.horizon.client.render.WebText
import net.minecraft.client.Minecraft
import org.joml.Matrix3x2f

/**
 * The HUD: the widgets in the Hud editor's list order, drawn under every screen. A frame draws each
 * enabled widget, then all queued plates, dividers and icons, then each widget's second pass, then the queued text.
 * The queues draw the new ClickGUI's look (design/clickgui/hud.css).
 */
object Hud {
    val widgets: List<HudWidget> by lazy {
        listOf(
            CoordWidget, FpsWidget, KeybindsWidget, PingWidget, PotionsWidget, StaffWidget, TargetInfo, TpsWidget,
            WatermarkWidget, NotificationsWidget, SpeedWidget, InventoryWidget, CrystalSpeedWidget, MediaPlayerWidget,
            ArmorWidget, ChestCounterWidget, PvpResourcesWidget, PlayerListWidget, Crosshair, FlightWidget, PhaseWidget,
            CooldownsWidget, MacroWidget,
            ArrayListWidget,
            SessionStatsWidget,
            KeystrokesWidget,
            ScoreboardWidget,
            HypixelStatsWidget,
        )
    }

    /** The last mouse position any screen was drawn with (stale while no screen is open). */
    var mouseX = 0f
    var mouseY = 0f

    /** The widget being dragged. */
    var dragged: HudWidget? = null

    /** The widget that set the cursor this frame. */
    var cursorOwner: HudWidget? = null

    class Plate(val m: Matrix3x2f, val x: Float, val y: Float, val w: Float, val h: Float, val alpha: Float, val radius: Float = 3.5f)
    class Divider(val m: Matrix3x2f, val x: Float, val y: Float, val alpha: Float = 1f)
    class IconDraw(val m: Matrix3x2f, val x: Float, val y: Float, val alpha: Float, val vector: VectorIcon)
    class TextDraw(val m: Matrix3x2f, val font: WebText, val s: String, val x: Float, val cy: Float, val left: Int, val right: Int)

    val plates = ArrayList<Plate>()
    val dividers = ArrayList<Divider>()
    val icons = ArrayList<IconDraw>()

    /** The new look's text, drawn last. */
    val texts = ArrayList<TextDraw>()

    /** The snap guides of the widget dragged in the new HUD editor (true: x of a vertical one). */
    val guides = ArrayList<Pair<Boolean, Float>>()

    private inline fun withMatrix(m: Matrix3x2f, draw: () -> Unit) {
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.set(m)
        draw()
        pose.popMatrix()
    }

    /** Once per frame in a world. */
    fun render() {
        cursorOwner = null
        guides.clear()
        for (w in widgets) if (w.enabled) w.frame()
        for (p in plates) withMatrix(p.m) { plate(p.x, p.y, p.w, p.h, p.radius, p.alpha) }
        plates.clear()
        for (d in dividers) withMatrix(d.m) {
            Canvas.quad(d.x, d.y, 0.5f, 8.5f, if (d.alpha >= 1f) 0x44FFFFFF else Colors.withAlpha(0x44FFFFFF, 0x44 / 255f * d.alpha))
        }
        dividers.clear()
        for (i in icons) withMatrix(i.m) { i.vector.draw(i.x, i.y, Colors.withAlpha(Tok.light(Tok.a1, 0.25f), i.alpha)) }
        icons.clear()
        for (w in widgets) if (w.enabled) w.postRender()
        Fonts.medium75.flush()
        for (t in texts) withMatrix(t.m) { t.font.drawGradientMiddle(t.s, t.x, t.cy, t.left, t.right) }
        texts.clear()
    }

    /**
     * The new look's plate (hud.css .pl): the blurred world under rgba(12, 11, 16, .52) with a white
     * 8% hairline, [alpha] for a row sliding in or out.
     */
    fun plate(x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float) {
        if (alpha <= 0f || w <= 0f || h <= 0f) return
        val pa = Canvas.alpha
        Canvas.alpha = pa * alpha.coerceIn(0f, 1f)
        Ui.blur(x, y, w, h, radius)
        Ui.box(x, y, w, h, radius, 0x850C0B10.toInt(), 0.5f, 0x14FFFFFF)
        Canvas.alpha = pa
    }

    /** Every mouse button event in a world, before the screen gets it. */
    fun mouse(button: Int, press: Boolean) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        for (w in widgets) if (w.enabled) w.mouse(button, press)
    }

    /** Every player tick. */
    fun tick() {
        for (w in widgets) if (w.enabled) w.tick()
    }
}

/** The new look's type (hud.css, CSS px / 2). */
internal object HudType {
    val text by lazy { WebText("sf_medium", 7.5f) }
    val bold8 by lazy { WebText("sf_bold", 8f) }
    val semi by lazy { WebText("sf_semibold", 7.5f) }
    val small by lazy { WebText("sf_medium", 6f) }
    val count by lazy { WebText("sf_semibold", 6.5f) }
    val tiny by lazy { WebText("sf_bold", 5.5f) }
    val letter by lazy { WebText("sf_semibold", 5.5f) }
    val media by lazy { WebText("sf_semibold", 7f) }
}
