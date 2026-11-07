package dev.horizon.client.gui.modern

import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.hud.Hud
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.VectorIcon
import dev.horizon.client.setting.BoolSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Not in the original: the HUD editor in the new style (UI > Style: New), built from design/clickgui/hud.js and hud.css.
 * The original's editor (hud/HudEditor) redesigned: a dashed frame 5 units inside the screen, and a Widgets panel in place of
 * its checkbox list, with a switch, a line on what it shows and a settings gear per widget, a search, All / On / Off,
 * and the HUD config. Widgets are dragged as in the original, snapping within 3 units to accent guides, and a right click
 * opens their settings. Hovering a row outlines its widget; clicking it makes the widget flash. The panel and the top
 * bar sit on the mockup's 800x450 stage like the Windows screen's; the panel moves by its header and stays where it is
 * left until the game closes.
 */
object ModernHudEditor : Screen(Component.literal("horizon-widget-editor")) {
    private val mc get() = Minecraft.getInstance()
    private val ui get() = Modules.get<ClickGuiModule>()

    private const val SW = ModernWindows.SW
    private const val SH = ModernWindows.SH
    private var k = 1f
    private var ox = 0f
    private var oy = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var openedAt = 0L
    private var closingAt = 0L

    // ---- the panel ----

    private const val PW = 165f
    private const val PMAX = 280f
    private var px = 320f
    private var py = 48f

    /** The mouse and the panel's place when its header was pressed. */
    private var grab: FloatArray? = null

    private var filter = 0
    private var scroll = 0f
    private val search = Ui.Field("", 32) { scroll = 0f }

    private var pop: Pop? = null

    /** The widget whose row the mouse is on (last frame), outlined as if hovered. */
    private var peek: HudWidget? = null
    private var nextPeek: HudWidget? = null
    private val flashAt = HashMap<HudWidget, Long>()

    /** hud.js's names: The original lists the ids ("Fps Widget", "Coord Widget"). */
    private val NAMES = mapOf("coord-widget" to "Coords", "fps-widget" to "FPS", "tps-widget" to "TPS", "keybinds-widget" to "Key Binds",
        "pvp-resources-widget" to "PvP Resources", "array-list-widget" to "ArrayList", "keystrokes-widget" to "Keystrokes",
        "hypixel-stats-widget" to "Hypixel Stats")

    private val DESC = mapOf(
        "coord-widget" to "Where you are, and in the Nether", "fps-widget" to "Frames per second",
        "keybinds-widget" to "Enabled modules that have a bind", "ping-widget" to "Your latency",
        "potions-widget" to "Active effects and the time left", "staff-widget" to "Staff detected on the server",
        "target-info" to "Your Aura or Auto Crystal target", "tps-widget" to "Server ticks per second, and the average",
        "watermark-widget" to "Your head, Horizon, your name and the server", "notifications" to "Pop-ups when modules turn on or off",
        "speed-widget" to "Your speed in km/h, mph or blocks/s", "inventory-widget" to "Your 27 inventory slots",
        "crystal-speed-widget" to "Auto Crystal's crystals per second", "media-player-widget" to "What's playing on your computer",
        "armor-widget" to "Your armour and its durability", "chest-counter-widget" to "Single and double chests nearby",
        "pvp-resources-widget" to "Totems, crystals, gapples, XP", "player-list-widget" to "Players nearby, health and distance",
        "crosshair" to "Spreads as you swing and move", "flight-widget" to "Compass, pitch and stats while gliding",
        "phase-widget" to "Your hitbox in the block you stand in", "cooldowns-widget" to "Items on cooldown, like pearls",
        "macro-widget" to "Your item macros and their keys", "array-list-widget" to "Every enabled module, with its mode",
        "session-stats-widget" to "Playtime, kills, deaths and K/D; on Hypixel also finals, FKDR, beds and wins",
        "keystrokes-widget" to "WASD, mouse buttons, spacebar and CPS",
        "hypixel-stats-widget" to "Your Hypixel level and Bedwars stars, FKDR, W/L, finals and wins (^hypixel key)",
    )

    /** The widgets without an icon of the original's get one drawn after what they show (assets/horizon/vector/gui). */
    private val FALLBACK = mapOf("pvp-resources-widget" to "totem", "notifications" to "bell", "media-player-widget" to "music",
        "crosshair" to "cross", "flight-widget" to "wings", "phase-widget" to "phasewall", "macro-widget" to "key")

    private val icons = HashMap<String, VectorIcon>()

    fun plain(w: HudWidget) = NAMES[w.id] ?: w.name.trim().removeSuffix(" Widget")

    private fun wicon(w: HudWidget, x: Float, y: Float, size: Float, color: Int) {
        val fb = FALLBACK[w.id]
        icons.getOrPut(w.id + "@" + size) {
            if (fb != null) VectorIcon("gui/$fb", size) else VectorIcon(if (w.id == "armor-widget") "armor" else w.id, size, mask = true)
        }.draw(x, y, color)
    }

    private fun bools(w: HudWidget) = w.settings.filterIsInstance<BoolSetting>().filter { it.id != "enabled" }

    // ---- the screen ----

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun isPauseScreen() = false

    override fun added() {
        super.added()
        mc.textInputManager().startTextInput(this)
        openedAt = System.currentTimeMillis()
        closingAt = 0L
        px = px.coerceIn(-125f, SW - 40f)
        py = py.coerceIn(0f, SH - 25f)
        Sounds.SETTINGS_OPEN.play()
    }

    override fun removed() {
        super.removed()
        mc.textInputManager().stopTextInput(this)
        Ui.focused = null
        pop = null
        grab = null
        peek = null
    }

    /** Esc: the panel fades out; [draw] closes the screen when it's gone. */
    override fun onClose() {
        if (closingAt != 0L) return
        closingAt = System.currentTimeMillis()
        Ui.focused = null
        pop = null
        Sounds.SETTINGS_CLOSE.play()
    }

    override fun onFilesDrop(paths: List<Path>) = Configs.filesDropped(paths)

    private fun toStage(x: Double, y: Double) {
        Ui.mx = (x.toFloat() - ox) / k
        Ui.my = (y.toFloat() - oy) / k
    }

    /** The mouse is on the panel, the top bar or a popover, so a press there doesn't grab a widget. */
    fun overUi(): Boolean {
        if (mc.gui.screen() !== this) return false
        val gx = mc.mouseHandler.getScaledXPos(mc.window).toFloat()
        val gy = mc.mouseHandler.getScaledYPos(mc.window).toFloat()
        return Ui.under((gx - ox) / k, (gy - oy) / k)
    }

    /** One frame, from the client's screen layer (Overlay), over the HUD. */
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
            super.onClose()
            return
        }
        Ui.mx = (mouseX - ox) / k
        Ui.my = (mouseY - oy) / k
        if (Ui.isDragging) Ui.drag()
        val overUi = Ui.overAny
        Ui.beginFrame()
        val inT = Easing.OUT_EXPO.apply(((now - openedAt) / 400.0).coerceIn(0.0, 1.0)).toFloat()

        Canvas.alpha = inT * fade
        frame(sw, sh)
        Canvas.alpha = fade
        outlines(now, overUi)
        guides(sw, sh)

        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(ox, oy)
        pose.scale(k, k)
        ModernWindows.topNav(inT, 2)
        nextPeek = null
        panel(now)
        peek = nextPeek
        pop?.let { if (!it.draw()) pop = null }
        pose.popMatrix()
        Canvas.alpha = 1f
    }

    // ---- the frame, the outlines, the guides (screen units) ----

    /** #hudframe: a dashed accent border 5 units in, over an inset shadow (inset 0 0 120px black 35%). */
    private fun frame(sw: Float, sh: Float) {
        val w = sw - 10f
        val h = sh - 10f
        // the shadow's Gaussian (sigma 30 units) as rings 2.5 wide, fading inwards
        for (i in 0 until 24) {
            val d = i * 2.5f
            val z = (d + 1.25f) / 30f
            val a = 0.35f * (1f - 1f / (1f + exp(-1.702f * z)))
            Ui.box(5f + d, 5f + d, w - 2f * d, h - 2f * d, max(0f, 7f - d), 0, 2.5f, Colors.withAlpha(0xFF000000.toInt(), a))
        }
        dashed(5f, 5f, w, h, 7f, 0.75f, Tok.alpha(Tok.a1, 0.55f), 1.5f, 1f)
    }

    /**
     * A CSS dashed border, [t] thick: [dash]-long dashes [gap] apart along the edges, and the corners' quarter circles
     * of radius [r] drawn solid.
     */
    private fun dashed(x: Float, y: Float, w: Float, h: Float, r: Float, t: Float, c: Int, dash: Float, gap: Float) {
        val n = (r * PI.toFloat() / 2f / 0.25f).toInt().coerceAtLeast(4)
        for ((cx, cy, a0) in listOf(Triple(x + r, y + r, PI), Triple(x + w - r, y + r, PI * 1.5), Triple(x + w - r, y + h - r, 0.0),
            Triple(x + r, y + h - r, PI * 0.5))) {
            for (i in 0..n) {
                val a = a0 + PI / 2 * i / n
                val rr = r - t / 2f
                Canvas.quad(cx + (cos(a) * rr).toFloat() - t / 2f, cy + (sin(a) * rr).toFloat() - t / 2f, t, t, c)
            }
        }
        var p = 0f
        val lw = w - 2f * r
        while (p < lw) {
            val l = min(dash, lw - p)
            Canvas.quad(x + r + p, y, l, t, c)
            Canvas.quad(x + r + p, y + h - t, l, t, c)
            p += dash + gap
        }
        p = 0f
        val lh = h - 2f * r
        while (p < lh) {
            val l = min(dash, lh - p)
            Canvas.quad(x, y + r + p, t, l, c)
            Canvas.quad(x + w - t, y + r + p, t, l, c)
            p += dash + gap
        }
    }

    /**
     * hud.css's .hw::after, 2.5 units around each widget: a dashed accent outline and a faint fill on hover (or while
     * its row is hovered), solid while dragged, and a flash when its row is clicked.
     */
    private fun outlines(now: Long, overUi: Boolean) {
        for (w in Hud.widgets) {
            if (!w.enabled || w.width <= 0f) continue
            val dragged = w.isDragged
            val hov = dragged || peek === w || (Hud.dragged == null && !overUi && pop == null && w.hovered())
            val t = Ui.anim("hw:" + w.id, if (hov) 1f else 0f, 200)
            val x = w.left - 2.5f
            val y = w.top - 2.5f
            val ww = w.width + 5f
            val hh = w.height + 5f
            val fl = flashAt[w]?.let { (now - it) / 1100f }?.takeIf { it < 1f }
            if (fl != null) {
                val a = if (fl < 0.4f) 1f else 1f - Easing.OUT_EXPO.apply(((fl - 0.4f) / 0.6f).toDouble()).toFloat()
                Ui.glow(x, y, ww, hh, 5f, 15f, Tok.alpha(Tok.a1, 0.45f * a))
                Ui.box(x, y, ww, hh, 5f, Tok.alpha(Tok.a1, 0.18f * a), 0.5f, Tok.alpha(Tok.a1, a))
            }
            if (t <= 0.01f) continue
            Ui.box(x, y, ww, hh, 5f, Tok.alpha(Tok.a1, 0.07f * t))
            if (dragged) Ui.box(x, y, ww, hh, 5f, 0, 0.5f, Tok.alpha(Tok.a1, t))
            else dashed(x, y, ww, hh, 5f, 0.5f, Tok.alpha(Tok.a1, 0.8f * t), 1.5f, 1.5f)
        }
    }

    /** The snap guides (.guide): accent hairlines with a glow, 5 units from the screen's edges. */
    private fun guides(sw: Float, sh: Float) {
        for ((vertical, at) in Hud.guides.distinct()) {
            if (vertical) {
                Ui.glow(at, 5f, 0.5f, sh - 10f, 0f, 4f, Tok.alpha(Tok.a1, 0.5f))
                Ui.box(at, 5f, 0.5f, sh - 10f, 0f, Tok.alpha(Tok.a1, 0.9f))
            } else {
                Ui.glow(5f, at, sw - 10f, 0.5f, 0f, 4f, Tok.alpha(Tok.a1, 0.5f))
                Ui.box(5f, at, sw - 10f, 0.5f, 0f, Tok.alpha(Tok.a1, 0.9f))
            }
        }
    }

    // ---- the panel (stage units) ----

    private fun shown(): List<HudWidget> {
        val q = search.text.trim().lowercase()
        return Hud.widgets.filter {
            (filter == 0 || (filter == 1) == it.enabled) &&
                (q.isEmpty() || plain(it).lowercase().contains(q) || (DESC[it.id] ?: "").lowercase().contains(q))
        }
    }

    private fun panel(now: Long) {
        val shown = shown()
        val content = if (shown.isEmpty()) 48f else shown.size * 24f + 5f
        val h = min(PMAX, 70.5f + content + 15.5f)
        val x = px
        val y = py
        val w = PW
        // the entrance: up 7 and from 96% over 0.6 s, in over 0.35 s
        val e = Easing.OUT_EXPO.apply(((now - openedAt) / 600.0).coerceIn(0.0, 1.0)).toFloat()
        val a = Easing.OUT_EXPO.apply(((now - openedAt) / 350.0).coerceIn(0.0, 1.0)).toFloat()
        val pose = Canvas.pose
        pose.pushMatrix()
        val s = 0.96f + 0.04f * e
        pose.translate(x + w / 2f, y + h / 2f + (1f - e) * 7f)
        pose.scale(s, s)
        pose.translate(-(x + w / 2f), -(y + h / 2f))
        val pa = Canvas.alpha
        Canvas.alpha = pa * a

        // the window's look (#hpanel)
        Ui.glow(x + 13f, y + 28f, w - 26f, h - 26f, 8f, 22f, 0xCC000000.toInt())
        Ui.blur(x, y, w, h, 8f)
        val g = ui.opacity.value
        Ui.vbox(x, y, w, h, 8f, Colors.withAlpha(0xFF18151E.toInt(), g), Colors.withAlpha(0xFF0B0A0F.toInt(), g + 0.06f), 0.5f, 0x1AFFFFFF)
        Ui.insetTop(x, y, w, 8f, 0x12FFFFFF)
        Ui.block("hpanel", x, y, w, h)

        header(x, y, w)
        searchField(x + 5.5f, y + 29.5f, 154f)
        Ui.seg("hseg", x + 5.5f, y + 50.5f, 154f, 17f, listOf("All", "On", "Off"), filter) {
            filter = it
            scroll = 0f
            Sounds.SWITCH_RIGHT.play()
        }
        list(shown, x, y + 70.5f, w, h - 70.5f - 15.5f)
        Ui.box(x, y + h - 15.5f, w, 0.5f, 0f, Tok.LINE)
        ModernWindows.hints(x, y + h - 15f, listOf("" to "drag a widget to move it", "RMB" to "settings", "Esc" to "done"))
        Canvas.alpha = pa
        pose.popMatrix()
    }

    /** The header (.whead): drags the panel; the icon, "Widgets", how many are on, and the HUD config. */
    private fun header(x: Float, y: Float, w: Float) {
        val hy = y + 12.25f
        Ui.hit("hhead", x, y, w, 24.5f, drag = { mx, my ->
            val d = grab ?: floatArrayOf(mx, my, px, py).also { grab = it }
            px = (d[2] + mx - d[0]).coerceIn(-125f, SW - 40f)
            py = (d[3] + my - d[1]).coerceIn(0f, SH - 25f)
        }, release = { grab = null }, pointer = false)
        Ui.box(x + 6.5f, hy - 7f, 14f, 14f, 4.5f, Tok.alpha(Tok.a1, 0.15f), 0.5f, Tok.alpha(Tok.a1, 0.24f))
        Ui.icon("layout", x + 9.5f, hy - 4f, 8f, Tok.light(Tok.a1, 0.2f))
        Ui.text(Type.wtitle, "Widgets", x + 25.5f, hy, Tok.T1)
        val count = "${Hud.widgets.count { it.enabled }}/${Hud.widgets.size} on"
        val cw = Type.chip.width(count) + 7f
        val ccx = x + 25.5f + Type.wtitle.width("Widgets") + 5f
        Ui.box(ccx, hy - 4.25f, cw, 8.5f, 3.5f, Tok.FILL)
        Ui.textC(Type.chip, count, ccx + cw / 2f, hy, Tok.T3)
        // .cfg: the HUD config, its menu below
        val name = Configs.of(ConfigType.WIDGETS).current.id
        val bw = 28f + Type.small.width(name)
        val bx = x + w - 5.5f - bw
        val by = y + 5.25f
        val over = Ui.hit("hcfg", bx, by, bw, 14f, { b ->
            if (b == Keys.MOUSE_LEFT) pop = if (pop is ConfigPop) null else ConfigPop(bx, by, bw, 14f)
        })
        Ui.box(bx, by, bw, 14f, 4f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, Tok.LINE)
        val c = if (over) Tok.T1 else Tok.T2
        Ui.icon("layout", bx + 5.5f, by + 4f, 6f, c)
        Ui.text(Type.small, name, bx + 14.5f, by + 7f, c)
        Ui.icon("down", bx + bw - 10.5f, by + 4f, 6f, c)
        Ui.box(x, y + 24f, w, 0.5f, 0f, Tok.LINE)
    }

    /** .fld.hsearch: the search icon, then the text. */
    private fun searchField(x: Float, y: Float, w: Float) {
        val f = search
        val focused = Ui.focused === f
        val over = Ui.hit(Ui.fieldId(f), x, y, w, 17f, { b -> if (b == Keys.MOUSE_LEFT) Ui.focused = f })
        if (focused) Ui.box(x - 1.5f, y - 1.5f, w + 3f, 20f, 6.5f, 0, 1.5f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(x, y, w, 17f, 5f, if (over && !focused) 0x47000000 else 0x38000000, 0.5f, if (focused) Tok.alpha(Tok.a1, 0.55f) else Tok.LINE2)
        Ui.icon("search", x + 6.5f, y + 4.75f, 7.5f, Tok.T3)
        val tx = x + 17.5f
        val cy = y + 8.5f
        val tw = w - 17.5f - 6f
        var shown = f.text
        while (shown.isNotEmpty() && Type.label.width(shown) > tw - 2f) shown = shown.drop(1)
        if (shown.isEmpty()) Ui.text(Type.label, "Search widgets", tx, cy, Tok.T4)
        else Ui.text(Type.label, shown, tx, cy, Tok.T1)
        if (focused && System.currentTimeMillis() % 1000 < 530) Ui.box(tx + Type.label.width(shown) + 0.5f, cy - 3.75f, 0.75f, 7.5f, 0f, Tok.a1)
    }

    /** .wl: a row per widget, 23 tall and 1 apart, scrolled with the wheel; "No widgets match" when the search finds none. */
    private fun list(shown: List<HudWidget>, x: Float, y: Float, w: Float, h: Float) {
        val content = if (shown.isEmpty()) 48f else shown.size * 24f + 5f
        val maxScroll = max(0f, content - h)
        scroll = scroll.coerceIn(0f, maxScroll)
        Ui.hit("hlist", x, y, w, h, scroll = { amount -> scroll = (scroll - amount.toFloat() * 24f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, y, w, h)
        if (shown.isEmpty()) {
            Ui.icon("search", x + w / 2f - 7.5f, y + 10f, 15f, Tok.T4)
            Ui.textC(Type.semi, "No widgets match", x + w / 2f, y + 10f + 15f + 4.5f + 4f, Tok.T2)
        }
        var ry = y + 1f - scroll
        for (wg in shown) {
            if (ry + 23f >= y && ry <= y + h) row(wg, x + 4.5f, ry)
            ry += 24f
        }
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + 157f, y + 1.5f, h - 3f, scroll, maxScroll)
    }

    /** .wli: the icon tile (in the theme colour when on), the name and its line, the settings gear, the switch. */
    private fun row(wg: HudWidget, x: Float, y: Float) {
        val w = 151f
        val on = wg.enabled
        val hasSettings = bools(wg).isNotEmpty()
        val over = Ui.hit("hrow:" + wg.id, x, y, w, 23f, { b ->
            if (b == Keys.MOUSE_LEFT && wg.enabled) flashAt[wg] = System.currentTimeMillis()
        })
        if (over) nextPeek = wg
        val t = Ui.anim("hon:" + wg.id, if (on) 1f else 0f, 250)
        if (over) Ui.box(x, y, w, 23f, 5.5f, 0x0BFFFFFF)
        Ui.box(x + 4.5f, y + 4f, 15f, 15f, 4.5f, Colors.lerp(0x06FFFFFF, Tok.alpha(Tok.a1, 0.16f), t), 0.5f,
            Colors.lerp(Tok.LINE, Tok.alpha(Tok.a1, 0.24f), t))
        wicon(wg, x + 7.75f, y + 7.25f, 8.5f, Colors.lerp(Tok.T2, Tok.light(Tok.a1, 0.15f), t))
        val textW = (if (hasSettings) 112f else 130f) - 5f - 24.5f
        Ui.text(Type.semi, Ui.fit(Type.semi, plain(wg), textW), x + 24.5f, y + 7.75f, Colors.lerp(Tok.T2, Tok.T1, t))
        Ui.text(Type.tiny, Ui.fit(Type.tiny, DESC[wg.id] ?: "", textW), x + 24.5f, y + 15.75f, Tok.T3)
        if (hasSettings) {
            val gx = x + 112f
            val gy = y + 5f
            val go = Ui.hit("hgear:" + wg.id, gx, gy, 13f, 13f, { b ->
                if (b == Keys.MOUSE_LEFT) pop = if ((pop as? SettingsPop)?.widget === wg) null else SettingsPop(wg, gx, gy, 13f, 13f)
            })
            if (go) Ui.box(gx, gy, 13f, 13f, 3.5f, Tok.FILL2)
            Ui.icon("gear", gx + 3f, gy + 3f, 7f, if (go) Tok.T1 else Tok.T4)
        }
        Ui.switch("hsw:" + wg.id, x + 130f, y + 6.5f, on) {
            wg.enabled = !wg.enabled
            if (wg.enabled) Sounds.ENABLE.play() else Sounds.DISABLE.play()
        }
    }

    // ---- popovers ----

    /** hud.js's popover (CL.openPop, aligned right): a card below its anchor, their right edges together. */
    private abstract class Pop(val ax: Float, val ay: Float, val aw: Float, val ah: Float) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        private val at = System.currentTimeMillis()

        fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
        fun anchorContains(px: Float, py: Float) = px >= ax && px <= ax + aw && py >= ay && py <= ay + ah

        abstract fun draw(): Boolean

        protected fun card(cw: Float, ch: Float) {
            w = cw
            h = ch
            x = (ax + aw - cw).coerceIn(4f, SW - 4f - cw)
            y = if (ay + ah + 3f + ch > SH - 4f) max(4f, ay - 3f - ch) else ay + ah + 3f
            val t = Easing.OUT_EXPO.apply(((System.currentTimeMillis() - at) / 250.0).coerceIn(0.0, 1.0)).toFloat()
            Canvas.alpha *= min(1f, t * 1.5f)
            val o = Modules.get<ClickGuiModule>().opacity.value
            Ui.box(x - 1f, y + 3f, w + 2f, h, 7.5f, 0x40000000)
            Ui.blur(x, y, w, h, 6.5f)
            Ui.box(x, y, w, h, 6.5f, Colors.withAlpha(0xFF18161F.toInt(), o + 0.12f), 0.5f, Tok.LINE2)
            Ui.block("hpop", x, y, w, h)
        }

        /** .pophead: the card's title, uppercase. */
        protected fun head(text: String) = Ui.text(Type.section, text.uppercase(), x + 8f, y + 9f, Tok.T4)

        /** .opt: a 15-tall row, an icon, the label, a tick when [checked]. */
        protected fun option(id: String, oy: Float, icon: String, label: String, checked: Boolean, click: () -> Unit) {
            val over = Ui.hit(id, x + 3f, oy, w - 6f, 15f, { if (it == Keys.MOUSE_LEFT) click() })
            if (over) Ui.box(x + 3f, oy, w - 6f, 15f, 4f, Tok.FILL2)
            val c = if (checked) Tok.WHITE else if (over) Tok.T1 else Tok.T2
            Ui.icon(icon, x + 8f, oy + 4f, 7f, c)
            Ui.text(Type.sub, Ui.fit(Type.sub, label, w - 40f), x + 19f, oy + 7.5f, c)
            if (checked) Ui.icon("check", x + w - 3f - 5f - 7f, oy + 4f, 7f, Tok.light(Tok.a1, 0.2f))
        }
    }

    /** A widget's settings: its booleans, each with a switch (the original's popup of checkboxes). */
    private class SettingsPop(val widget: HudWidget, ax: Float, ay: Float, aw: Float, ah: Float) : Pop(ax, ay, aw, ah) {
        override fun draw(): Boolean {
            val list = bools(widget)
            card(116f, 3f + 11f + max(1, list.size) * 15f + 3f)
            head(plain(widget))
            if (list.isEmpty()) Ui.text(Type.sub, "No settings", x + 8f, y + 14f + 7.5f, Tok.T2)
            for ((i, s) in list.withIndex()) {
                val oy = y + 14f + i * 15f
                val over = Ui.hit("hset:" + s.id, x + 3f, oy, w - 6f, 15f, { b ->
                    if (b == Keys.MOUSE_LEFT) {
                        s.value = !s.value
                        if (s.value) Sounds.ENABLE.play() else Sounds.DISABLE.play()
                    }
                })
                if (over) Ui.box(x + 3f, oy, w - 6f, 15f, 4f, Tok.FILL2)
                Ui.text(Type.sub, s.displayName, x + 8f, oy + 7.5f, if (over) Tok.T1 else Tok.T2)
                Ui.switch("hset:" + widget.id + ":" + s.id, x + w - 8f - 17f, oy + 2.5f, s.value)
            }
            return true
        }
    }

    /** The HUD configs: load one, or save the layout under a new name (the Windows screen's Save as). */
    private class ConfigPop(ax: Float, ay: Float, aw: Float, ah: Float) : Pop(ax, ay, aw, ah) {
        override fun draw(): Boolean {
            val configs = Configs.of(ConfigType.WIDGETS).list.toList()
            card(106f, 3f + 11f + configs.size * 15f + 5.5f + 15f + 3f)
            head("HUD configs")
            val current = Configs.of(ConfigType.WIDGETS).current.id
            var oy = y + 14f
            for (c in configs) {
                option("hcfg:" + c.id, oy, "layout", c.id, c.id == current) {
                    pop = null
                    ConfigsPane.load(c.id, ConfigType.WIDGETS)
                }
                oy += 15f
            }
            Ui.box(x + 5f, oy + 2.5f, w - 10f, 0.5f, 0f, Tok.LINE)
            oy += 5.5f
            option("hcfg-save", oy, "save", "Save as...", false) {
                pop = null
                ModernWindows.open("configs")
                ConfigsPane.saveHud()
            }
            return true
        }
    }

    // ---- input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        toStage(event.x(), event.y())
        val b = event.button()
        val p = pop
        if (p != null && !p.contains(Ui.mx, Ui.my) && !p.anchorContains(Ui.mx, Ui.my)) pop = null
        val hadFocus = Ui.focused
        val id = Ui.press(b)
        if (hadFocus != null && Ui.focused === hadFocus && id != Ui.fieldId(hadFocus)) Ui.focused = null
        // a right click on a widget (not on the panel): its settings, under it
        if (b == Keys.MOUSE_RIGHT && id == null) {
            val ex = event.x().toFloat()
            val ey = event.y().toFloat()
            val w = Hud.widgets.firstOrNull {
                it.enabled && it.width > 0f && ex >= it.left && ex <= it.left + it.width && ey >= it.top && ey <= it.top + it.height
            }
            if (w != null) {
                pop = SettingsPop(w, (w.left - 2.5f - ox) / k, (w.top - 2.5f - oy) / k, (w.width + 5f) / k, (w.height + 5f) / k)
                Sounds.SETTINGS_OPEN.play()
            }
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        toStage(event.x(), event.y())
        Ui.release()
        grab = null
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

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val f = Ui.focused
        if (key == Keys.ESCAPE) {
            when {
                pop != null -> pop = null
                f === search -> {
                    search.text = ""
                    scroll = 0f
                    Ui.focused = null
                }
                f != null -> Ui.focused = null
                else -> onClose()
            }
            return true
        }
        if (f != null) {
            if (key == Keys.ENTER) Ui.focused = null else f.key(key)
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
        val f = Ui.focused ?: return true
        for (c in event.codepointAsString()) f.type(c)
        return true
    }
}
