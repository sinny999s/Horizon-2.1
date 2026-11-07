package dev.horizon.client.gui.modern

import dev.horizon.client.config.Configs
import dev.horizon.client.config.Macro
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Blur
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Theme
import dev.horizon.client.setting.Bind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Not in the original: the Windows screen in the new style (UI > Style: New), built from design/clickgui/windows.js and
 * windows.css. The original's five windows (gui/windows/) with the same data and actions, redesigned: every entry has its
 * own controls (eye, radio, switch, bind) and a click opens it in the editor (right click too, as in the original); windows
 * come forward when clicked and grow from their corner; Delete asks once more; Homies removes a friend from its trash
 * button. The windows sit on the mockup's 800x450 stage (its 1600x900 halved), centred and shrunk to fit the screen;
 * where they are moved to is kept until the game closes, as the original's.
 */
object ModernWindows : Screen(Component.literal("horizon-windows")) {
    private val mc get() = Minecraft.getInstance()
    private val ui get() = Modules.get<ClickGuiModule>()

    const val SW = 800f
    const val SH = 450f
    private var k = 1f
    private var ox = 0f
    private var oy = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var openedAt = 0L
    private var closingAt = 0L

    internal val panes: List<Pane> by lazy { listOf(ConfigsPane, WaypointsPane, ProxiesPane, MacrosPane, HomiesPane) }

    /** Back to front: the last is the front window. */
    private val order = ArrayList<Pane>()

    /** The right-click menu, over everything. */
    internal var menu: Menu? = null

    /** The macro waiting for its bind: the next key or button becomes it. */
    internal var listening: Macro? = null
    private var listenedAt = 0L

    /** The window being moved or resized, and where the drag started. */
    private class Drag(val pane: Pane, val mx: Float, val my: Float, val x: Float, val y: Float, val w: Float, val h: Float, val resize: Boolean)
    private var drag: Drag? = null

    // ---- the screen ----

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // From the main menu there's no world behind: the menu's own panorama instead.
        if (only != null && mc.level == null) extractPanorama(graphics, delta)
    }

    override fun isPauseScreen() = false

    override fun added() {
        super.added()
        mc.textInputManager().startTextInput(this)
        openedAt = System.currentTimeMillis()
        closingAt = 0L
        if (order.isEmpty()) order.addAll(panes)
        for (p in panes) {
            p.x = p.x.coerceIn(40f - p.w, SW - 40f)
            p.y = p.y.coerceIn(0f, SH - 24f)
            p.opened()
        }
        dev.horizon.client.config.Proxies.pingAll()
        Sounds.SETTINGS_OPEN.play()
    }

    override fun removed() {
        super.removed()
        leaveMenu()
        mc.textInputManager().stopTextInput(this)
        Ui.focused = null
        menu = null
        listening = null
        drag = null
        for (p in panes) p.dialog = null
    }

    /** Esc: the windows fade out; [draw] closes the screen when they're gone. */
    override fun onClose() {
        if (closingAt != 0L) return
        closingAt = System.currentTimeMillis()
        Ui.focused = null
        menu = null
        Sounds.SETTINGS_CLOSE.play()
    }

    override fun onFilesDrop(paths: List<Path>) = Configs.filesDropped(paths)

    /** The proxies' "+" dialog is open: a dropped file is a proxy list (the original's import). */
    val proxyDialogOpen get() = ProxiesPane.dialog != null

    fun proxiesImported() {
        ProxiesPane.dialog = null
        Ui.focused = null
    }

    private fun toStage(x: Double, y: Double) {
        Ui.mx = (x.toFloat() - ox) / k
        Ui.my = (y.toFloat() - oy) / k
    }

    /** One frame, from the client's screen layer (Overlay). */
    fun draw() {
        val sw = width.toFloat()
        val sh = height.toFloat()
        k = min(1f, min(sw / SW, sh / SH))
        ox = (sw - SW * k) / 2f
        oy = (sh - SH * k) / 2f
        val now = System.currentTimeMillis()
        val fade = if (closingAt != 0L) 1f - ((now - closingAt) / 180f).coerceIn(0f, 1f) else 1f
        if (closingAt != 0L && fade <= 0f) {
            closingAt = 0L
            val to = back
            if (only != null) mc.gui.setScreen(to) else super.onClose()
            return
        }
        // Out of a world nothing else moves the theme along or captures what the panes blur.
        if (only != null && mc.player == null) {
            Theme.update()
            Blur.capture()
        }
        Ui.mx = (mouseX - ox) / k
        Ui.my = (mouseY - oy) / k
        if (Ui.isDragging) Ui.drag()
        Ui.beginFrame()

        // the world behind: blurred, dimmed, the vignette
        val inT = Easing.OUT_EXPO.apply(((now - openedAt) / 500.0).coerceIn(0.0, 1.0)).toFloat()
        Canvas.alpha = inT * fade
        Ui.blur(0f, 0f, sw, sh, 0f)
        Ui.box(0f, 0f, sw, sh, 0f, 0x61000000)
        vignette(sw, sh)

        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(ox, oy)
        pose.scale(k, k)
        // From the main menu, no coloured glows: the menus out of a world are black through white.
        if (only == null || mc.level != null) Ui.ambient(0f, 0f)
        Canvas.alpha = fade
        if (only == null) topNav(inT, 1)
        for (p in order.toList()) if (only == null || p === only) window(p, if (only != null) 0 else panes.indexOf(p), fade)
        menu?.let { if (!it.draw()) menu = null }
        pose.popMatrix()
        Canvas.alpha = 1f
    }

    /** radial-gradient(110% 85% at 50% 50%, black .05 40%, black .55): a 3x3 grid of corner-coloured quads. */
    private fun vignette(sw: Float, sh: Float) {
        val c = 0x0D000000
        val e = 0x17000000
        val m = 0x36000000
        val corner = 0x54000000
        val hw = sw / 2f
        val hh = sh / 2f
        // corners, the middles of the edges (left/right reach less of the gradient than top/bottom) and the centre
        Canvas.quad(0f, 0f, hw, hh, e, c, m, corner)
        Canvas.quad(hw, 0f, hw, hh, c, e, corner, m)
        Canvas.quad(0f, hh, hw, hh, corner, m, c, e)
        Canvas.quad(hw, hh, hw, hh, m, corner, e, c)
    }

    // ---- the top navigation (the original's top bar: UI / Windows / Hud) ----

    /** The top bar of the new Windows screen and HUD editor, [active] being 1 or 2; [t] is the screen's entrance. */
    internal fun topNav(t: Float, active: Int) {
        val tabs = listOf(Triple("Modules", "grid", 0), Triple("Windows", "macro", 1), Triple("HUD", "layout", 2))
        val tabW = tabs.map { 7f + 8f + 4f + Type.label.width(it.first) + 7f }
        val escW = 2f + Type.chip.width("Esc") + 6f + 3f + Type.small.width("close") + 5f
        val w = 6f + 13f + 5f + tabW.sum() + 2f * tabs.size + 3f + 0.5f + 3f + escW + 3f
        val x = SW / 2f - w / 2f
        val y = 12f - (1f - t) * 6f
        val h = 23f
        Ui.glow(x + 10f, y + 10f, w - 20f, h - 20f, 7.5f, 14f, 0x99000000.toInt())
        Ui.blur(x, y, w, h, 7.5f)
        Ui.box(x, y, w, h, 7.5f, Colors.withAlpha(0xFF100E15.toInt(), 0.74f), 0.5f, 0x17FFFFFF)
        Ui.insetTop(x, y, w, 7.5f, 0x0FFFFFFF)
        Ui.block("nav", x, y, w, h)
        var cx = x + 6f
        Ui.glow(cx + 2.5f, y + 7.5f, 8f, 8f, 4f, 4f, Tok.alpha(Tok.a1, 0.35f))
        Ui.hit("nav:logo", cx - 1f, y + 3f, 15f, 17f, { if (it == Keys.MOUSE_LEFT) mc.gui.setScreen(ModernGui) })
        Ui.mark(cx, y + 5f, 13f)
        cx += 13f + 5f
        for ((i, tab) in tabs.withIndex()) {
            val (label, icon, n) = tab
            val tw = tabW[i]
            val on = n == active
            val over = Ui.hit("navtab:$label", cx, y + 3f, tw, 17f, { b ->
                if (b != Keys.MOUSE_LEFT || on) return@hit
                when (n) {
                    0 -> mc.gui.setScreen(ModernGui)
                    1 -> mc.gui.setScreen(ModernWindows)
                    2 -> mc.gui.setScreen(ModernHudEditor)
                }
            })
            if (on) {
                Ui.hbox(cx, y + 3f, tw, 17f, 5f, Tok.alpha(Tok.light(Tok.a1, 0.12f), 0.22f), 0x0FFFFFFF, 0.5f, 0x14FFFFFF)
            } else if (over) {
                Ui.box(cx, y + 3f, tw, 17f, 5f, Tok.FILL)
            }
            val c = if (on) Tok.WHITE else if (over) Tok.T1 else Tok.T2
            Ui.icon(icon, cx + 7f, y + 7.5f, 8f, if (on) Tok.light(Tok.a1, 0.15f) else c)
            Ui.text(Type.label, label, cx + 19f, y + 11.5f, c)
            cx += tw + 2f
        }
        cx += 1f
        Ui.box(cx, y + 6.5f, 0.5f, 10f, 0f, Tok.LINE2)
        cx += 3.5f + 2f
        cx += Ui.keycap(cx, y + 7f, "Esc") + 3f
        Ui.text(Type.small, "close", cx, y + 11.5f, Tok.T3)
    }

    // ---- a window ----

    /** The window's entrance, 0..1: they come in one after another, 80 + 55 ms apart. */
    private fun entrance(i: Int): Float {
        val t = (System.currentTimeMillis() - openedAt - 80L - 55L * i) / 600.0
        return Easing.OUT_EXPO.apply(t.coerceIn(0.0, 1.0)).toFloat()
    }

    private fun window(p: Pane, i: Int, @Suppress("UNUSED_PARAMETER") fade: Float) {
        val e = entrance(i)
        if (e <= 0.001f) return
        val front = order.lastOrNull() === p
        val pose = Canvas.pose
        pose.pushMatrix()
        val s = 0.96f + 0.04f * e
        val cx = p.x + p.w / 2f
        val cy = p.y + p.h / 2f
        pose.translate(cx, cy + (1f - e) * 8f)
        pose.scale(s, s)
        pose.translate(-cx, -cy)
        val pa = Canvas.alpha
        Canvas.alpha = pa * e
        val x = p.x
        val y = p.y
        val w = p.w
        val h = p.h
        // shadow (0 30px 80px -26px, the front one 0 40px 100px -26px), the front one's ring in the accent
        if (front) Ui.glow(x + 13f, y + 33f, w - 26f, h - 26f, 8f, 28f, 0xD9000000.toInt())
        else Ui.glow(x + 13f, y + 28f, w - 26f, h - 26f, 8f, 22f, 0xCC000000.toInt())
        if (front) Ui.box(x - 0.5f, y - 0.5f, w + 1f, h + 1f, 8.5f, 0, 0.5f, Tok.alpha(Tok.a1, 0.2f))
        val pulse = ((System.currentTimeMillis() - p.pulseAt) / 1000f).takeIf { it in 0f..1f }
        if (pulse != null) {
            val a = if (pulse < 0.35f) 1f else 1f - (pulse - 0.35f) / 0.65f
            Ui.glow(x, y, w, h, 8f, 20f, Tok.alpha(Tok.a1, 0.5f * a))
            Ui.box(x - 1f, y - 1f, w + 2f, h + 2f, 9f, 0, 1f, Tok.alpha(Tok.a1, 0.7f * a))
        }
        Ui.blur(x, y, w, h, 8f)
        Ui.pushClip(x, y, w, h)
        Ui.ambient(0f, 0f, floatArrayOf(x, y, w, h, 8f))
        Ui.popClip()
        val g = ui.opacity.value
        Ui.vbox(x, y, w, h, 8f, Colors.withAlpha(0xFF18151E.toInt(), g), Colors.withAlpha(0xFF0B0A0F.toInt(), g + 0.06f),
            0.5f, if (front) 0x21FFFFFF else 0x17FFFFFF)
        Ui.insetTop(x, y, w, 8f, if (front) 0x14FFFFFF else 0x12FFFFFF)
        Ui.block("win:" + p.id, x, y, w, h)
        Ui.pushClip(x, y, w, h)

        // the header: icon, title, count, tools; dragging it moves the window
        val hy = y + 12.25f
        Ui.hit("head:" + p.id, x, y, w, 24.5f, drag = { mx, my ->
            val d = drag ?: Drag(p, mx, my, p.x, p.y, p.w, p.h, false).also { drag = it }
            if (!d.resize) {
                p.x = (d.x + mx - d.mx).coerceIn(40f - p.w, SW - 40f)
                p.y = (d.y + my - d.my).coerceIn(0f, SH - 24f)
            }
        }, release = { drag = null }, pointer = false)
        Ui.box(x + 6.5f, hy - 7f, 14f, 14f, 4.5f, Tok.alpha(Tok.a1, 0.15f), 0.5f, Tok.alpha(Tok.a1, 0.24f))
        Ui.icon(p.icon, x + 9.5f, hy - 4f, 8f, Tok.light(Tok.a1, 0.2f))
        Ui.text(Type.wtitle, p.title, x + 25.5f, hy, Tok.T1)
        val count = "${p.count}"
        val cw = Type.chip.width(count) + 7f
        val ccx = x + 25.5f + Type.wtitle.width(p.title) + 5f
        Ui.box(ccx, hy - 4.25f, cw, 8.5f, 3.5f, Tok.FILL)
        Ui.textC(Type.chip, count, ccx + cw / 2f, hy, Tok.T3)
        p.tools(x + w - 5.5f, y + 4.75f)
        Ui.box(x, y + 24f, w, 0.5f, 0f, Tok.LINE)

        // the body, the foot
        val by = y + 24.5f
        val bh = h - 24.5f - 15.5f
        p.body(x + 0.5f, by, w - 1f, bh)
        foot(p, x, y + h - 15.5f, w)

        // the grip: drag it to grow the window (not below its starting size)
        val gx = x + w - 2f - 7f
        val gy = y + h - 2f - 7f
        val gOver = Ui.hit("grip:" + p.id, gx - 2f, gy - 2f, 11f, 11f, drag = { mx, my ->
            val d = drag ?: Drag(p, mx, my, p.x, p.y, p.w, p.h, true).also { drag = it }
            if (d.resize) {
                p.w = max(p.w0, d.w + mx - d.mx)
                p.h = max(p.h0, d.h + my - d.my)
            }
        }, release = { drag = null })
        val ga = if (gOver || drag?.let { it.pane === p && it.resize } == true) 0xB3FFFFFF.toInt() else 0x4DFFFFFF
        // linear-gradient(135deg, ...): a stripe across the corner square at 45-52%, a shorter one at 66-73%
        for (d in floatArrayOf(0f, 0.35f)) {
            Canvas.line(gx + d, gy + 6.8f + d, gx + 6.8f + d, gy + d, ga)
            Canvas.line(gx + 2.7f + d, gy + 7f + d, gx + 7f + d, gy + 2.7f + d, ga)
        }

        // the dialog: the body dims behind a small card
        p.dialog?.let { dialog(p, it, x + 0.5f, by, w - 1f, h - 25f) }
        Ui.popClip()
        Canvas.alpha = pa
        pose.popMatrix()
    }

    private fun foot(p: Pane, x: Float, y: Float, w: Float) {
        Ui.box(x, y, w, 0.5f, 0f, Tok.LINE)
        hints(x, y + 0.5f, p.hints)
    }

    /** A window foot's hints (.wfoot) from [y] (under its line): a key (or "") and what it does. */
    internal fun hints(x: Float, y: Float, hints: List<Pair<String, String>>) {
        val cy = y + 7.25f
        var hx = x + 7.5f
        for ((key, text) in hints) {
            if (key.isNotEmpty()) {
                hx += smallKey(hx, cy - 4f, key) + 2.5f
            }
            Ui.text(Type.tiny, text, hx, cy, Tok.T3)
            hx += Type.tiny.width(text) + 6f
        }
    }

    /** A keycap 8 tall with 10px text (the foot's). */
    fun smallKey(x: Float, y: Float, text: String): Float {
        val w = Type.ver.width(text) + 5f
        Ui.box(x, y, w, 8f, 2.5f, 0x0FFFFFFF, 0.5f, 0x1AFFFFFF)
        Ui.box(x + 0.5f, y + 7f, w - 1f, 0.5f, 0f, 0x1AFFFFFF)
        Ui.textC(Type.ver, text, x + w / 2f, y + 3.75f, Tok.T2)
        return w
    }

    // ---- dialogs ----

    private fun dialog(p: Pane, d: Dialog, x: Float, y: Float, w: Float, h: Float) {
        val t = Easing.OUT_EXPO.apply(((System.currentTimeMillis() - d.opened) / 320.0).coerceIn(0.0, 1.0)).toFloat()
        val pa = Canvas.alpha
        Canvas.alpha = pa * min(1f, t * 1.6f)
        Ui.box(x, y, w, h, 0f, 0x8C08070B.toInt())
        Ui.hit("dim:" + p.id, x, y, w, h, { close(p) }, scroll = { true }, pointer = false)
        val cw = 135f
        val extraH = if (d.extra != null) 18f + 5f else 0f
        val textLines = d.text?.let { Ui.wrap(Type.status, it, cw - 14f, 3) } ?: emptyList()
        val ch = 7f + 8f + (if (textLines.isEmpty()) 0f else 5f - 2f + textLines.size * 8f) + 5f + 17f + extraH + 5f + 2f + 16f + 7f
        val cx = x + (w - cw) / 2f
        val cy = y + (h - ch) / 2f + (1f - t) * 6f
        Ui.glow(cx + 8f, cy + 20f, cw - 16f, ch - 16f, 7.5f, 16f, 0xCC000000.toInt())
        Ui.blur(cx, cy, cw, ch, 7.5f)
        Ui.box(cx, cy, cw, ch, 7.5f, Colors.withAlpha(0xFF1E1B25.toInt(), ui.opacity.value + 0.15f), 0.5f, Tok.LINE2)
        Ui.block("dlg:" + p.id, cx, cy, cw, ch)
        var yy = cy + 7f
        Ui.text(Type.wtitle, d.title, cx + 7f, yy + 4f, Tok.T1)
        yy += 8f
        if (textLines.isNotEmpty()) {
            yy += 3f
            for (l in textLines) {
                Ui.text(Type.status, l, cx + 7f, yy + 4f, Tok.T3)
                yy += 8f
            }
        }
        yy += 5f
        field(d.field, cx + 7f, yy, cw - 14f, d.label, 33f, "")
        yy += 17f
        d.extra?.let {
            yy += 5f
            it(cx + 7f, yy, cw - 14f)
            yy += 18f
        }
        yy += 5f + 2f
        val ready = d.field.text.isNotBlank()
        val okW = buttonWidth(d.create, null)
        val okX = cx + cw - 7f - okW
        wbtn("dlg-ok:" + p.id, okX, yy, d.create, null, primary = true, enabled = ready, h = 16f) { submit(p) }
        val caW = buttonWidth("Cancel", null)
        wbtn("dlg-cancel:" + p.id, okX - 4f - caW, yy, "Cancel", null, h = 16f) { close(p) }
        Canvas.alpha = pa
    }

    internal fun openDialog(p: Pane, d: Dialog) {
        p.dialog = d
        Ui.focused = d.field
        raise(p)
        Sounds.SETTINGS_OPEN.play()
    }

    private fun close(p: Pane) {
        if (Ui.focused === p.dialog?.field) Ui.focused = null
        p.dialog = null
    }

    private fun submit(p: Pane) {
        val d = p.dialog ?: return
        val name = d.field.text.trim()
        if (name.isEmpty()) return
        close(p)
        d.onCreate(name)
    }

    // ---- shared controls ----

    fun buttonWidth(label: String?, icon: String?, h: Float = 15f): Float =
        if (label == null) h else 12f + Type.sub.width(label) + (if (icon != null) 10f else 0f)

    /** The mockup's .wbtn (30px): bordered, or [primary] in the accent gradient; returns its width. */
    fun wbtn(id: String, x: Float, y: Float, label: String?, icon: String?, primary: Boolean = false, enabled: Boolean = true,
             h: Float = 15f, w: Float? = null, click: () -> Unit): Float {
        val bw = w ?: buttonWidth(label, icon, h)
        val over = Ui.hit("wbtn:$id", x, y, bw, h, { if (it == Keys.MOUSE_LEFT && enabled) click() }) && enabled
        val pa = Canvas.alpha
        if (!enabled) Canvas.alpha = pa * 0.4f
        if (primary) {
            Ui.pushClip(x - 12f, y + h, bw + 24f, 14f)
            Ui.glow(x + 4f, y + h - 4f, bw - 8f, 4f, 4.5f, if (over) 8f else 6f, Tok.alpha(Tok.a1, if (over) 0.7f else 0.5f))
            Ui.popClip()
            Ui.dbox(x, y, bw, h, 4.5f, Tok.a1, Tok.a2)
        } else {
            Ui.box(x, y, bw, h, 4.5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        }
        val c = if (primary) Tok.WHITE else if (over) Tok.T1 else Tok.T2
        val content = (if (icon != null) 7f else 0f) + (if (icon != null && label != null) 3f else 0f) + (label?.let { Type.sub.width(it) } ?: 0f)
        var cx = x + (bw - content) / 2f
        if (icon != null) {
            Ui.icon(icon, cx, y + (h - 7f) / 2f, 7f, c)
            cx += 10f
        }
        if (label != null) Ui.text(Type.sub, label, cx, y + h / 2f, c)
        Canvas.alpha = pa
        return bw
    }

    private val armed = HashMap<String, Long>()

    /** The mockup's Delete: the first click arms it for two seconds ("Click again to delete"), the second runs it. */
    fun deleteButton(id: String, x: Float, y: Float, w: Float, label: String, run: () -> Unit) {
        val at = armed[id]?.takeIf { System.currentTimeMillis() - it < 2000 }
        val over = Ui.hit("del:$id", x, y, w, 18f, { b ->
            if (b != Keys.MOUSE_LEFT) return@hit
            if (at != null) {
                armed.remove(id)
                run()
            } else {
                armed[id] = System.currentTimeMillis()
                Sounds.SWITCH_LEFT.play()
            }
        })
        var dx = 0f
        if (at != null) {
            val t = (System.currentTimeMillis() - at) / 350f
            if (t < 1f) dx = (sin(t * Math.PI * 2).toFloat()) * 1.5f
            Ui.pushClip(x - 12f, y + 18f, w + 24f, 12f)
            Ui.glow(x + 4f, y + 14f, w - 8f, 4f, 5f, 6f, 0x99E5484D.toInt())
            Ui.popClip()
            Ui.box(x + dx, y, w, 18f, 5f, 0xFFE5484D.toInt())
        } else {
            Ui.box(x, y, w, 18f, 5f, if (over) 0x26FF4646 else 0x17FF4646, 0.5f, 0x42FF5A5A)
        }
        val text = if (at != null) "Click again to delete" else label
        val c = if (at != null) Tok.WHITE else 0xFFFF8F8F.toInt()
        val tw = 7f + 3.5f + Type.value.width(text)
        Ui.icon("trash", x + dx + (w - tw) / 2f, y + 5.5f, 7f, c)
        Ui.text(Type.value, text, x + dx + (w - tw) / 2f + 10.5f, y + 9f, c)
    }

    /** The mockup's empty state: a big faint icon, a title and a line, centred on ([cx], [cy]). */
    fun empty(icon: String, title: String, text: String, cx: Float, cy: Float, w: Float) {
        val lines = Ui.wrap(Type.sub, text, w - 20f, 3)
        val total = 15f + 4.5f + 7.75f + 4.5f + lines.size * 9.06f
        var y = cy - total / 2f
        Ui.icon(icon, cx - 7.5f, y, 15f, Tok.T4)
        y += 15f + 4.5f
        Ui.textC(Type.semi, title, cx, y + 3.9f, Tok.T2)
        y += 7.75f + 4.5f
        for (l in lines) {
            Ui.textC(Type.sub, l, cx, y + 4.5f, Tok.T3)
            y += 9.06f
        }
    }

    /** What a text field shows besides its text. */
    class FieldStyle(val mask: Boolean = false, val numeric: Boolean = false, val suggest: (() -> List<String>)? = null)

    internal val styles = HashMap<Ui.Field, FieldStyle>()
    private val revealed = HashSet<Ui.Field>()
    private val suggestion = HashMap<Ui.Field, String>()

    /**
     * The mockup's "label | value" field (34px): [labelW] wide label (null: the label's own width plus padding, as in a
     * row of fields), the text, a Tab completion from its style's suggestions, and an eye for masked text.
     */
    internal fun field(f: Ui.Field, x: Float, y: Float, w: Float, label: String, labelW: Float?, placeholder: String) {
        val style = styles[f]
        val focusedNow = Ui.focused === f
        val over = Ui.hit(Ui.fieldId(f), x, y, w, 17f, { b -> if (b == Keys.MOUSE_LEFT) Ui.focused = f })
        if (focusedNow) Ui.box(x - 1.5f, y - 1.5f, w + 3f, 20f, 6.5f, 0, 1.5f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(x, y, w, 17f, 5f, if (over && !focusedNow) 0x47000000 else 0x38000000, 0.5f,
            if (focusedNow) Tok.alpha(Tok.a1, 0.55f) else Tok.LINE2)
        val cy = y + 8.5f
        val lw = labelW ?: (6f + Type.small.width(label) + 5f)
        if (label.isNotEmpty()) Ui.text(Type.small, label, x + 6.5f, cy, Tok.T3)
        val tx = x + 0.5f + lw
        var right = x + w - 6.5f
        if (style?.mask == true) {
            val shown = f in revealed
            val rx = x + w - 0.5f - 2.5f - 14f
            val ro = Ui.hit("reveal:" + System.identityHashCode(f), rx, y + 1.5f, 14f, 14f, { b ->
                if (b == Keys.MOUSE_LEFT) { if (!revealed.remove(f)) revealed += f }
            })
            if (ro) Ui.box(rx, y + 1.5f, 14f, 14f, 3.5f, Tok.FILL2)
            Ui.icon(if (shown) "eyeoff" else "eye", rx + 3.25f, y + 4.75f, 7.5f, if (ro) Tok.T1 else Tok.T3)
            right = rx - 2f
        }
        val sg = if (focusedNow && style?.suggest != null && f.text.isNotEmpty())
            style.suggest.invoke().firstOrNull { it.startsWith(f.text) && it != f.text } else null
        if (sg != null) {
            suggestion[f] = sg
            val kw = Type.chip.width("Tab") + 6f
            Ui.keycap(x + w - 0.5f - 4f - kw, y + 4f, "Tab")
            right = x + w - 0.5f - 4f - kw - 3f
        } else {
            suggestion.remove(f)
        }
        val masked = style?.mask == true && f !in revealed
        val text = if (masked) "•".repeat(f.text.length) else f.text
        val tw = right - tx
        if (text.isEmpty()) {
            if (placeholder.isNotEmpty()) Ui.text(Type.label, Ui.fit(Type.label, placeholder, tw), tx, cy, Tok.T4)
        } else {
            // a browser's input: its start when idle, its end (the caret) while typing
            var shown = text
            if (focusedNow) while (shown.isNotEmpty() && Type.label.width(shown) > tw - 2f) shown = shown.drop(1)
            else while (shown.isNotEmpty() && Type.label.width(shown) > tw) shown = shown.dropLast(1)
            Ui.pushClip(tx, y, max(0f, tw), 17f)
            Ui.text(Type.label, shown, tx, cy, Tok.T1)
            Ui.popClip()
            if (sg != null) Ui.text(Type.label, Ui.fit(Type.label, sg.substring(f.text.length), max(0f, tw - Type.label.width(shown))),
                tx + Type.label.width(shown), cy, Tok.T4)
        }
        if (focusedNow && System.currentTimeMillis() % 1000 < 530) {
            val cw = if (text.isEmpty()) 0f else min(tw - 2f, Type.label.width(text))
            Ui.box(tx + cw + 0.5f, cy - 3.75f, 0.75f, 7.5f, 0f, Tok.a1)
        }
    }

    /** CSS's uppercase section label in an editor (.flabel); returns its height. */
    fun flabel(text: String, x: Float, y: Float): Float {
        Ui.text(Type.flabel, text.uppercase(), x + 2f, y + 3f + 3.15f, Tok.T4)
        return 9.3f
    }

    fun notify(title: String, text: String, type: NotificationType = NotificationType.INFO) =
        Notifications.post(title, text, 2f, type)

    // ---- input ----

    internal fun raise(p: Pane) {
        if (order.lastOrNull() === p) return
        order.remove(p)
        order += p
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        toStage(event.x(), event.y())
        val b = event.button()
        val l = listening
        if (l != null && System.currentTimeMillis() - listenedAt > 80) {
            l.bind = Bind(b, false, true)
            listening = null
            Sounds.SETTINGS_OPEN.play()
            return true
        }
        val m = menu
        if (m != null && !m.contains(Ui.mx, Ui.my)) menu = null
        // the window under the mouse comes forward
        if (menu == null) order.lastOrNull { Ui.mx >= it.x && Ui.mx <= it.x + it.w && Ui.my >= it.y && Ui.my <= it.y + it.h }?.let { raise(it) }
        val hadFocus = Ui.focused
        val id = Ui.press(b)
        if (hadFocus != null && Ui.focused === hadFocus && id != Ui.fieldId(hadFocus) && id?.startsWith("reveal:") != true) Ui.focused = null
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        toStage(event.x(), event.y())
        Ui.release()
        drag = null
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        toStage(event.x(), event.y())
        Ui.drag()
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        toStage(x, y)
        Ui.scroll(scrollY)
        return true
    }

    fun listen(m: Macro) {
        listening = m
        listenedAt = System.currentTimeMillis()
        Sounds.SWITCH_RIGHT.play()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val l = listening
        if (l != null) {
            l.bind = if (key == Keys.ESCAPE || key == Keys.BACKSPACE || key == Keys.DELETE) Bind() else Bind(key, false, false)
            listening = null
            Sounds.SETTINGS_OPEN.play()
            return true
        }
        val f = Ui.focused
        val dialogPane = panes.firstOrNull { it.dialog != null && it.dialog?.field === f }
        if (key == Keys.ESCAPE) {
            when {
                menu != null -> menu = null
                dialogPane != null -> close(dialogPane)
                panes.any { it.dialog != null } -> panes.first { it.dialog != null }.let { close(it) }
                f != null -> Ui.focused = null
                else -> onClose()
            }
            return true
        }
        if (f != null) {
            when (key) {
                Keys.TAB -> suggestion[f]?.let { sg ->
                    f.text = sg
                    f.onChange(sg)
                }
                Keys.ENTER -> when {
                    dialogPane != null -> submit(dialogPane)
                    f === HomiesPane.add -> HomiesPane.submit()
                    else -> Ui.focused = null
                }
                else -> f.key(key)
            }
            return true
        }
        val b = ui.bind
        if (!b.mouse && b.key == key) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (listening != null) return true
        val f = Ui.focused ?: return true
        val numeric = styles[f]?.numeric == true
        for (c in event.codepointAsString()) {
            if (numeric && !(c.isDigit() || (c == '-' && f.text.isEmpty()))) continue
            f.type(c)
        }
        return true
    }

    // ---- from the main menu ----

    /** The one pane shown when opened from the main menu, and the screen Esc goes back to. */
    private var only: Pane? = null
    private var back: Screen? = null
    private var inGameX = 0f
    private var inGameY = 0f

    /**
     * Not in the original: the main menu's Proxies button. Only the proxies pane shows, centred over the menu's
     * panorama, so a proxy can be picked (and is used) before the first connection; Esc goes back to [from]. The pane's
     * in-game place is put back afterwards.
     */
    fun openProxies(from: Screen) {
        val p = ProxiesPane
        if (only == null) {
            inGameX = p.x
            inGameY = p.y
        }
        only = p
        back = from
        p.x = (SW - p.w) / 2f
        p.y = (SH - p.h) / 2f
        mc.gui.setScreen(this)
    }

    private fun leaveMenu() {
        val p = only ?: return
        p.x = inGameX
        p.y = inGameY
        only = null
        back = null
    }

    /** Called by the classic UI's top bar and the new GUI: open with [focus] pulsing. */
    fun open(focus: String? = null) {
        mc.gui.setScreen(this)
        if (focus != null) panes.firstOrNull { it.id == focus }?.let {
            raise(it)
            it.pulseAt = System.currentTimeMillis() + 450
        }
    }
}

/** One of the screen's windows: its place (moved and grown by the user), header, body and foot. */
internal abstract class Pane(val id: String, val title: String, val icon: String, x0: Float, y0: Float, val w0: Float, val h0: Float) {
    var x = x0
    var y = y0
    var w = w0
    var h = h0
    var dialog: Dialog? = null
    var pulseAt = 0L

    abstract val count: Int

    /** The foot's hints: a key (or "") and what it does. */
    abstract val hints: List<Pair<String, String>>

    /** The header's buttons, right-aligned to [right], 15 tall from [y]. */
    open fun tools(right: Float, y: Float) {}

    abstract fun body(x: Float, y: Float, w: Float, h: Float)

    open fun opened() {}
}

/** A window's small dialog (the original's Name / Create): a name field, maybe more, Cancel and the action. */
internal class Dialog(val title: String, val text: String?, max: Int, val create: String = "Create", val label: String = "Name",
                      val extra: ((Float, Float, Float) -> Unit)? = null, val onCreate: (String) -> Unit) {
    val field = Ui.Field("", max)
    val opened = System.currentTimeMillis()
}

/** The right-click menu: icon and label rows; red ones delete. */
internal class Menu(private val ax: Float, private val ay: Float, private val items: List<Item>) {
    class Item(val icon: String, val label: String, val danger: Boolean = false, val run: () -> Unit)

    private var x = 0f
    private var y = 0f
    private var w = 0f
    private var h = 0f
    private val at = System.currentTimeMillis()

    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h

    fun draw(): Boolean {
        w = max(85f, (items.maxOfOrNull { Type.label.width(it.label) } ?: 40f) + 26f)
        h = items.size * 15f + 6f
        x = ax.coerceIn(4f, ModernWindows.SW - 4f - w)
        y = (ay - 3f).coerceIn(4f, ModernWindows.SH - 4f - h)
        val t = Easing.OUT_EXPO.apply(((System.currentTimeMillis() - at) / 250.0).coerceIn(0.0, 1.0)).toFloat()
        val pa = Canvas.alpha
        Canvas.alpha = pa * min(1f, t * 1.5f)
        val o = Modules.get<ClickGuiModule>().opacity.value
        Ui.box(x - 1f, y + 3f, w + 2f, h, 7.5f, 0x40000000)
        Ui.blur(x, y, w, h, 6.5f)
        Ui.box(x, y, w, h, 6.5f, Colors.withAlpha(0xFF18161F.toInt(), o + 0.12f), 0.5f, Tok.LINE2)
        Ui.block("menu", x, y, w, h)
        for ((i, it) in items.withIndex()) {
            val oy = y + 3f + i * 15f
            val over = Ui.hit("menu:$i", x + 3f, oy, w - 6f, 15f, { b ->
                if (b == Keys.MOUSE_LEFT) {
                    ModernWindows.menu = null
                    it.run()
                }
            })
            if (over) Ui.box(x + 3f, oy, w - 6f, 15f, 4f, if (it.danger) 0x1FFF4646 else Tok.FILL2)
            val c = if (it.danger) 0xFFFF8F8F.toInt() else if (over) Tok.T1 else Tok.T2
            Ui.icon(it.icon, x + 8f, oy + 4f, 7f, c)
            Ui.text(Type.label, it.label, x + 19f, oy + 7.5f, c)
        }
        Canvas.alpha = pa
        return true
    }
}
