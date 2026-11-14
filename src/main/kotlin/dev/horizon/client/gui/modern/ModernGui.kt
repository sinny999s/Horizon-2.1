package dev.horizon.client.gui.modern

import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.legit.GhostMode
import dev.horizon.client.module.SubCategory
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.BindSetting
import dev.horizon.client.setting.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The redesigned ClickGUI (UI > Style: New), built from design/clickgui/index.html. One glass window
 * over the blurred world: a sidebar of the categories and their tabs, a grid of module cards, and an inspector
 * with the selected module's settings. Controls: left click a card toggles it, right click opens
 * its settings, middle click binds it, Ctrl+Shift+click resets it, Alt shows every bind; typing anywhere searches.
 * Windows and HUD open their own screens.
 */
object ModernGui : Screen(Component.literal("horizon-modern")) {
    // the window, in units (the mockup's px / 2)
    const val W = 620f
    const val H = 350f
    private const val BAR = 29f
    private const val STATUS = 15f
    private const val SIDE = 107f
    private const val INSP = 186f

    private val mc get() = Minecraft.getInstance()
    private val ui get() = Modules.get<ClickGuiModule>()

    sealed class View(val key: String) {
        class Sub(val sub: SubCategory) : View("sub:" + sub.id)
        object Active : View("active")
        object Fav : View("fav")
    }

    enum class Filter { ALL, ON, OFF }

    var view: View = View.Sub(Categories.ATTACK)
    private var filter = Filter.ALL
    var selected: Module? = null
    private var viewChanged = 0L
    private var viewDir = 0f

    /** Waiting for a key or mouse button to bind [Capture.setting] (of [Capture.module]). */
    class Capture(val module: Module, val setting: BindSetting, val started: Long = System.currentTimeMillis())
    var capture: Capture? = null

    /** The open popover, drawn over everything; a click outside closes it. */
    internal var popover: Popover? = null

    private var closing = false
    private var opened = false
    private var mouseX = 0f
    private var mouseY = 0f
    private var ox = 0f
    private var oy = 0f
    private var k = 1f
    private var gridScroll = 0f
    private var gridScrollMax = 0f
    private val shakes = HashMap<Module, Long>()

    // search
    private val search = Ui.Field(max = 40) { runSearch() }
    private var hits: List<SearchHit> = emptyList()
    private var cur = 0

    class SearchHit(val module: Module, val setting: Setting<*>?)

    override fun isPauseScreen() = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        this.mouseX = mouseX.toFloat()
        this.mouseY = mouseY.toFloat()
    }

    override fun added() {
        super.added()
        mc.textInputManager().startTextInput(this)
        closing = false
        opened = true
        Ui.animFrom("open", 600, 0f)
        if (selected == null) selected = shown().firstOrNull { it.id == "auto-crystal" } ?: shown().firstOrNull { it.id == "aura" }
        enterView(0f)
    }

    override fun removed() {
        super.removed()
        mc.textInputManager().stopTextInput(this)
        Ui.focused = null
        capture = null
        popover = null
        hits = emptyList()
    }

    /** Esc: fade out; [draw] closes the screen when it's done. */
    override fun onClose() {
        if (closing) return
        closing = true
        Ui.focused = null
        popover = null
        Ui.animFrom("open", 180, 1f, Easing.IN_QUAD)
    }

    override fun onFilesDrop(paths: List<Path>) = Configs.filesDropped(paths)

    // ---- the frame ----

    /** One frame, from the client's screen layer (Overlay). */
    fun draw() {
        val sw = width.toFloat()
        val sh = height.toFloat()
        k = min(1f, min((sw - 16f) / W, (sh - 16f) / H))
        ox = (sw - W * k) / 2f
        oy = (sh - H * k) / 2f
        val t = if (closing) Ui.anim("open", 0f, 180, Easing.IN_QUAD) else Ui.anim("open", 1f, 600)
        if (closing && t <= 0.02f) {
            closing = false
            super.onClose()
            return
        }
        Ui.mx = (mouseX - ox) / k
        Ui.my = (mouseY - oy) / k
        if (Ui.isDragging) Ui.drag()
        Ui.beginFrame()

        // the world behind, blurred and dimmed
        Canvas.alpha = t
        Ui.blur(0f, 0f, sw, sh, 0f)
        Ui.box(0f, 0f, sw, sh, 0f, 0x61000000)

        val pose = Canvas.pose
        pose.pushMatrix()
        val s = k * (0.965f + 0.035f * t)
        pose.translate(ox + W * k / 2f, oy + H * k / 2f + (1f - t) * 7f)
        pose.scale(s, s)
        pose.translate(-W / 2f, -H / 2f)
        window()
        pose.popMatrix()
        Canvas.alpha = 1f
    }

    /** The window's tint at a height (its glass: [window]). */
    private fun tintAt(y: Float): Int {
        val g = ui.opacity.value
        return Ui.vboxAt(0f, H, Colors.withAlpha(0xFF18151E.toInt(), g), Colors.withAlpha(0xFF0B0A0F.toInt(), g + 0.06f), y)
    }

    /** The window's background, for fading the ends of its scrolling lists ([Ui.fade]); the inspector's is a shade darker. */
    private val backdrop = Ui.Backdrop({ Ui.ambient(-90f, -50f) }, { y -> tintAt(y) })
    internal val inspectorBackdrop = Ui.Backdrop({ Ui.ambient(-90f, -50f) }, { y -> tintAt(y) }, { 0x24000000 })

    private fun window() {
        // box-shadow: 0 50px 140px -30px rgba(0, 0, 0, .85), 0 0 0 1px rgba(0, 0, 0, .35)
        Ui.glow(15f, 40f, W - 30f, H - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(-0.5f, -0.5f, W + 1f, H + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(0f, 0f, W, H, 11f)
        // the stage's origin is 90, 50 from the window's
        Ui.ambient(-90f, -50f)
        val g = ui.opacity.value
        Ui.vbox(0f, 0f, W, H, 11f, Colors.withAlpha(0xFF18151E.toInt(), g), Colors.withAlpha(0xFF0B0A0F.toInt(), g + 0.06f), 0.5f, 0x17FFFFFF)
        Ui.insetTop(0f, 0f, W, 11f, 0x12FFFFFF)
        Ui.fadeLine(W * 0.1f, 0f, W * 0.8f, 0.5f, Tok.alpha(Tok.light(Tok.a1, 0.3f), 0.55f))
        Ui.block("window", 0f, 0f, W, H)
        titleBar()
        sidebar()
        main()
        Inspector.draw(W - INSP, BAR, INSP, H - BAR - STATUS)
        statusBar()
        searchResults()
        popover?.let {
            if (!it.draw()) popover = null
        }
    }

    // ---- title bar ----

    private const val SEARCH_W = 260f
    private val tools = listOf("Lunar" to "lunar", "Windows" to "windows", "HUD" to "layout", "Theme" to "theme")
    private fun toolW(label: String) = Type.sub.width(label) + 22.5f

    /** The search box's left: centred between the brand (98 wide) and the tools, as the mockup's flex row. */
    private val searchX: Float get() {
        val toolsLeft = W - 8.5f - tools.sumOf { toolW(it.first).toDouble() }.toFloat() - 2f * (tools.size - 1)
        return 114.5f + (toolsLeft - 7f - 114.5f - SEARCH_W) / 2f
    }

    private fun titleBar() {
        Ui.box(0f, BAR, W, 0.5f, 0f, Tok.LINE)
        Ui.glow(12.5f, 10.75f, 8f, 8f, 4f, 5f, Tok.alpha(Tok.a1, 0.3f))
        Ui.mark(9.5f, 7.75f, 14f)
        Type.brand.drawGradientMiddle("Horizon", 28.5f, 14.75f, Tok.a1, Tok.a2)
        val vx = 28.5f + Type.brand.width("Horizon") + 5f
        val vw = Type.ver.width("26.3") + 7f
        Ui.box(vx, 10.25f, vw, 9f, 3f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.textC(Type.ver, "26.3", vx + vw / 2f, 14.75f, Tok.T3)

        // the search field
        val sx = searchX
        val focusedNow = Ui.focused === search
        val over = Ui.hit(Ui.fieldId(search), sx, 5.75f, SEARCH_W, 18f, { if (it == Keys.MOUSE_LEFT) Ui.focused = search })
        if (focusedNow) Ui.box(sx - 2f, 3.75f, SEARCH_W + 4f, 22f, 7.5f, 0, 2f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(sx, 5.75f, SEARCH_W, 18f, 5.5f, if (focusedNow) 0x59000000 else 0x40000000, 0.5f,
            if (focusedNow) Tok.alpha(Tok.a1, 0.55f) else if (over) 0x26FFFFFF else Tok.LINE2)
        Ui.icon("search", sx + 6f, 10.75f, 8f, Tok.T3)
        val total = shown().sumOf { m -> m.settings.count { GuiData.valued(it) && it.id !in Module.META } }
        Ui.fieldText(search, sx + 19f, 14.75f, SEARCH_W - 54f, Type.label, "Search ${shown().size} modules and $total settings")
        Ui.keycap(sx + SEARCH_W - 4.5f - Type.chip.width("Ctrl F") - 6f, 10.25f, "Ctrl F")

        // tools, right to left
        var x = W - 8.5f
        for ((label, icon) in tools.reversed()) {
            val run: () -> Unit = when (label) {
                "Theme" -> { { popover = if (popover is ThemeMenu) null else ThemeMenu(x0("Theme"), BAR + 4f) } }
                "HUD" -> { { mc.gui.setScreen(ModernHudEditor) } }
                "Lunar" -> { { popover = if (popover is LunarMenu) null else LunarMenu(x0("Lunar") - 150f, BAR + 4f) } }
                else -> { { mc.gui.setScreen(ModernWindows) } }
            }
            val w = toolW(label)
            x -= w
            toolX[label] = x
            val o = Ui.hit("tool:$label", x, 6.25f, w, 17f, { if (it == Keys.MOUSE_LEFT) run() })
            val on = (label == "Theme" && popover is ThemeMenu) || (label == "Lunar" && popover is LunarMenu)
            if (o || on) Ui.box(x, 6.25f, w, 17f, 5f, Tok.FILL2)
            // Lunar's own logo for its menu.
            if (icon == "lunar") dev.horizon.client.lunar.LunarNameTag.canvasLogo(x + 5f, 10.25f, 9f, if (o || on) Tok.T1 else Tok.T2, 0)
            else Ui.icon(icon, x + 5.5f, 10.75f, 8f, if (o || on) Tok.T1 else Tok.T2)
            Ui.text(Type.sub, label, x + 17f, 14.75f, if (o || on) Tok.T1 else Tok.T2)
            x -= 2f
        }
    }

    private val toolX = HashMap<String, Float>()
    private fun x0(label: String) = (toolX[label] ?: (W - 60f)) + toolW(label) - 150f

    // ---- sidebar ----

    private fun navKeys(): List<View> =
        Categories.ALL.flatMap { c -> c.subCategories.filter { shows(it) }.map { View.Sub(it) } } + listOf(View.Active, View.Fav)

    /**
     * The modules the GUI shows. With Ghost Mode on, the blatant ones it blocks are hidden (and a
     * tab left with none, Rage, with them).
     */
    fun shown(): List<Module> = if (GhostMode.enabled) Modules.all.filter { !GhostMode.blocks(it) } else Modules.all

    private fun shows(s: SubCategory) = !GhostMode.enabled || shown().any { it.subCategory == s }

    private fun subName(s: SubCategory) = if (s.id == "esp") "ESP" else s.displayName

    private var navScroll = 0f
    private var navScrollMax = 0f

    /** The mockup's sidebar: 16-tall category labels and 17-tall tabs, scrolling (no bar) above the profile chip. */
    private fun sidebar() {
        // Ghost Mode turned on with a hidden tab or module open: move off it.
        (view as? View.Sub)?.let { v -> if (!shows(v.sub)) navKeys().firstOrNull()?.let { view = it; enterView(0f) } }
        selected?.let { m -> if (GhostMode.blocks(m)) selected = mods(view).firstOrNull() ?: shown().firstOrNull() }
        Ui.box(SIDE, BAR, 0.5f, H - BAR - STATUS, 0f, Tok.LINE)
        val top = BAR + 6.5f
        val bottom = PROFILE_Y - 4f
        val sy = Ui.anim("navScroll", navScroll, 300)
        var y = top - sy
        val items = ArrayList<Pair<View, Float>>()
        val rows = ArrayList<() -> Unit>()
        fun item(v: View, label: String, icon: String) {
            val iy = y
            items += v to iy
            rows += { navItem(v, label, icon, iy) }
            y += 17f
        }
        fun cat(label: String) {
            val ly = y
            rows += { Ui.text(Type.section, label.uppercase(Locale.ROOT), 10.5f, ly + 10f, Tok.T4) }
            y += 16f
        }
        for (c in Categories.ALL) {
            cat(c.displayName)
            for (s in c.subCategories) if (shows(s)) item(View.Sub(s), subName(s), s.id)
        }
        cat("Collections")
        item(View.Active, "Enabled", "active")
        item(View.Fav, "Favourites", "fav")
        navScrollMax = max(0f, y + sy - bottom)
        navScroll = navScroll.coerceIn(0f, navScrollMax)
        Ui.hit("nav", 0f, top, SIDE, bottom - top, scroll = { a -> navScroll = (navScroll - a.toFloat() * 17f).coerceIn(0f, navScrollMax); true }, pointer = false)
        Ui.pushClip(0f, top, SIDE, bottom - top)
        // the pill under the selected tab
        val sel = items.firstOrNull { it.first.key == view.key }?.second ?: top
        val py = Ui.anim("pill", sel + sy, 500) - sy
        Ui.hbox(5.5f, py, 96.5f, 17f, 5f, Tok.alpha(Tok.light(Tok.a1, 0.14f), 0.185f), 0x0BFFFFFF, 0.5f, 0x12FFFFFF)
        rows.forEach { it() }
        Ui.popClip()
        // the list's ends fade out while there is more that way
        Ui.fade(backdrop, 1f, top, SIDE - 1f, NAV_FADE, false, sy / NAV_FADE)
        Ui.fade(backdrop, 1f, bottom, SIDE - 1f, NAV_FADE, true, (navScrollMax - sy) / NAV_FADE)
        profile()
    }

    private fun navItem(v: View, label: String, icon: String, y: Float) {
        val active = v.key == view.key
        val over = Ui.hit("nav:" + v.key, 5.5f, y, 96.5f, 17f, { if (it == Keys.MOUSE_LEFT) go(v) })
        val c = if (active) Tok.WHITE else if (over) Tok.T1 else Tok.T2
        Ui.icon(icon, 10.5f, y + 4.5f, 8f, if (active) Tok.light(Tok.a1, 0.15f) else c)
        Ui.text(Type.nav, label, 23.5f, y + 8.5f, c)
        val list = mods(v)
        val on = list.count { it.enabled }
        val count = if (v is View.Sub) "$on/${list.size}" else "${list.size}"
        val has = if (v is View.Sub) on > 0 else list.isNotEmpty()
        val cw = Type.chip.width(count) + 7f
        Ui.box(97f - cw, y + 4.25f, cw, 8.5f, 3.5f, if (has) Tok.alpha(Tok.a1, 0.15f) else Tok.FILL)
        Ui.textC(Type.chip, count, 97f - cw / 2f, y + 8.5f, if (has) Tok.light(Tok.a1, 0.25f) else Tok.T3)
    }

    private const val PROFILE_Y = H - STATUS - 6f - 27f

    /** How far from their ends the sidebar's list and the module grid fade out ([Ui.fade]). */
    private const val NAV_FADE = 8f
    private const val GRID_FADE = 10f

    /** For ShotBot: opens the profile menu (configs, public configs, Ghost Mode...). */
    @JvmStatic
    fun testProfile() {
        popover = ProfileMenu(5.5f, PROFILE_Y)
    }

    private fun profile() {
        val y = PROFILE_Y
        val over = Ui.hit("profile", 5.5f, y, 96.5f, 27f, { if (it == Keys.MOUSE_LEFT) popover = if (popover is ProfileMenu) null else ProfileMenu(5.5f, y) })
        Ui.box(5.5f, y, 96.5f, 27f, 6.5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        Ui.dbox(11f, y + 5.5f, 16f, 16f, 4.5f, Tok.a1, Tok.a2)
        val cfg = Configs.of(ConfigType.MODULES).current.id
        Ui.textC(Type.semi, cfg.take(1).uppercase(Locale.ROOT), 19f, y + 13.5f, Tok.WHITE)
        Ui.box(23f, y + 17.5f, 5f, 5f, 2.5f, 0xFF34D77B.toInt(), 1f, 0xFF17151C.toInt())
        Ui.text(Type.value, Ui.fit(Type.value, cfg, 52f), 32f, y + 10.25f, Tok.T1)
        val server = mc.currentServer?.ip ?: "singleplayer"
        Ui.text(Type.tiny, Ui.fit(Type.tiny, "$server · ${ServerInfo.ping} ms", 54f), 32f, y + 17.25f, Tok.T3)
        Ui.icon("updown", 88.5f, y + 9.5f, 8f, Tok.T3)
    }

    fun mods(v: View): List<Module> {
        val all = shown()
        return when (v) {
            is View.Sub -> all.filter { it.subCategory == v.sub }.sortedBy { it.id.lowercase(Locale.ROOT) }
            View.Active -> all.filter { it.enabled }.sortedBy { it.id.lowercase(Locale.ROOT) }
            View.Fav -> all.filter { GuiData.isFav(it) }.sortedBy { it.id.lowercase(Locale.ROOT) }
        }
    }

    fun go(v: View) {
        if (v.key == view.key) return
        val keys = navKeys().map { it.key }
        val dir = if (keys.indexOf(v.key) >= keys.indexOf(view.key)) 1f else -1f
        if (dir > 0) Sounds.SWITCH_RIGHT.play() else Sounds.SWITCH_LEFT.play()
        view = v
        enterView(dir)
    }

    private fun enterView(dir: Float) {
        viewDir = dir
        viewChanged = System.currentTimeMillis()
        gridScroll = 0f
    }

    // ---- main: header and the card grid ----

    private fun main() {
        val x0 = SIDE
        val w = W - SIDE - INSP
        val v = view
        val all = mods(v)
        val list = all.filter { filter == Filter.ALL || (filter == Filter.ON) == it.enabled }
        val title = when (v) {
            is View.Sub -> subName(v.sub)
            View.Active -> "Enabled"
            View.Fav -> "Favourites"
        }
        val left = x0 + 11.5f
        Ui.text(Type.title, title, left, 45.4f, Tok.T1)
        val on = all.count { it.enabled }
        val prefix = (if (v is View.Sub) Categories.ALL.first { v.sub in it.subCategories }.displayName.let { if (it == "Pvp") "PvP" else it } + " · " else "") +
            "${all.size} module${if (all.size == 1) "" else "s"} · "
        Ui.text(Type.sub, prefix, left, 58.5f, Tok.T3)
        Ui.text(Type.value, "$on enabled", left + Type.sub.width(prefix), 58.5f, Tok.light(Tok.a1, 0.3f))
        val fl = listOf("All", "Enabled", "Disabled")
        val fw = Ui.segWidth(fl)
        Ui.seg("filter", x0 + w - 11.5f - fw, 46.25f, fw, 16f, fl, filter.ordinal, plain = true, natural = true) {
            filter = Filter.entries[it]
            Sounds.SWITCH_RIGHT.play()
            enterView(0f)
        }

        val gy = 68.25f
        val gh = H - STATUS - gy
        val cardH = 52f
        val rows = (list.size + 1) / 2
        gridScrollMax = max(0f, 2f + rows * (cardH + 5f) - 5f + 11f - gh)
        // a scrolling grid gives 5 to its scrollbar's gutter, as the browser does
        val cardW = (w - 23f - 5f - (if (gridScrollMax > 0f) 5f else 0f)) / 2f
        gridScroll = gridScroll.coerceIn(0f, gridScrollMax)
        val sy = Ui.anim("gridScroll", gridScroll, 300)
        Ui.hit("grid", x0, gy, w, gh, scroll = { a -> gridScroll = (gridScroll - a.toFloat() * 26f).coerceIn(0f, gridScrollMax); true }, pointer = false)
        Ui.pushClip(x0, gy, w, gh)
        if (list.isEmpty()) {
            Ui.textC(Type.label, if (v == View.Fav && all.isEmpty()) "Star a module to keep it here." else "Nothing here.", x0 + w / 2f, gy + 40f, Tok.T3)
        }
        val now = System.currentTimeMillis()
        for ((i, m) in list.withIndex()) {
            val cx = left + (i % 2) * (cardW + 5f)
            val cy = gy + 2f + (i / 2) * (cardH + 5f) - sy
            if (cy + cardH < gy - 4f || cy > gy + gh + 4f) continue
            val p = ((now - viewChanged - min(i, 24) * 14L) / 550.0).coerceIn(0.0, 1.0)
            val e = Easing.OUT_EXPO.apply(p).toFloat()
            card(m, cx + viewDir * 17f * (1f - e), cy + (if (viewDir == 0f) 3f * (1f - e) else 0f), cardW, cardH, e, v !is View.Sub)
        }
        Ui.popClip()
        Ui.fade(backdrop, x0 + 1f, gy, w - 1f, GRID_FADE, false, sy / GRID_FADE)
        Ui.fade(backdrop, x0 + 1f, gy + gh, w - 1f, GRID_FADE, true, (gridScrollMax - sy) / GRID_FADE)
        if (gridScrollMax > 0f) scrollbar(x0 + w - 3.5f, gy + 1.5f, gh - 3f, sy, gridScrollMax)
    }

    fun scrollbar(x: Float, y: Float, h: Float, scroll: Float, max: Float) {
        val th = max(16f, h * h / (h + max))
        Ui.box(x, y + (h - th) * (scroll / max), 2f, th, 1f, 0x1AFFFFFF)
    }

    private fun card(m: Module, x: Float, y: Float, w: Float, h: Float, appear: Float, showSub: Boolean) {
        val prevAlpha = Canvas.alpha
        Canvas.alpha = prevAlpha * appear
        val id = "card:" + m.id
        val on = m.enabled
        val capturing = capture?.module === m && capture?.setting === m.bindSetting
        val over = Ui.hit(id, x, y, w, h, { b -> cardClick(m, b) })
        val lift = Ui.anim("lift:" + m.id, if (over) 1f else 0f, 350)
        val yy = y - lift
        val t = Ui.anim("on:" + m.id, if (on) 1f else 0f, 400)
        val border = Colors.lerp(if (over) 0x1FFFFFFF else 0x11FFFFFF, Tok.alpha(Tok.a1, 0.42f), t)
        // shadows: selected 0 12px 32px -14px black, on 0 10px 30px -14px the accent; only below the card, as CSS
        // draws a box-shadow outside the box and the card is see-through
        Ui.pushClip(x - 12f, yy + h, w + 24f, 24f)
        if (selected === m) Ui.glow(x + 7f, yy + 13f, w - 14f, h - 14f, 7.5f, 8f, 0x80000000.toInt())
        if (t > 0.01f) Ui.glow(x + 7f, yy + 12f, w - 14f, h - 14f, 7.5f, 8f, Tok.alpha(Tok.a1, 0.4f * t))
        Ui.popClip()
        Ui.box(x, yy, w, h, 7.5f, Colors.lerp(if (over) 0x0DFFFFFF else 0x08FFFFFF, 0x08FFFFFF, t), 0.5f, border)
        if (t > 0.01f) {
            Ui.dbox(x, yy, w, h, 7.5f, Tok.alpha(Tok.a1, 0.17f * t), Tok.alpha(Tok.a2, 0.05f * t))
            Ui.box(x + 0.5f, yy + 0.5f, w - 1f, h - 1f, 7f, 0, 0.5f, Tok.alpha(Tok.a1, 0.1f * t))
            Ui.fadeLine(x + 8f, yy, w - 16f, 0.5f, Tok.alpha(Tok.light(Tok.a1, 0.2f), t))
        }
        // the hover light following the mouse (radial-gradient(240px circle at the mouse, white .075, transparent 60%))
        val spot = Ui.anim("spot:" + m.id, if (over) 1f else 0f, 300)
        if (spot > 0.01f) {
            Canvas.radial(Ui.mx, Ui.my, 72f, 72f, Colors.withAlpha(-1, 0.075f * spot), floatArrayOf(x, yy, w, h, 7.5f))
        }
        if (selected === m) Ui.box(x - 0.75f, yy - 0.75f, w + 1.5f, h + 1.5f, 8.25f, 0, 0.75f, 0x8CFFFFFF.toInt())
        if (capturing) {
            val pulse = (0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 190.0).toFloat())
            Ui.box(x - 1.5f, yy - 1.5f, w + 3f, h + 3f, 9f, 0, 1.5f, Tok.alpha(Tok.a1, 0.25f + 0.35f * pulse))
        }
        // a wiggle when jumped to
        val shake = shakes[m]?.let { System.currentTimeMillis() - it }?.takeIf { it < 550 }
        val dx = if (shake != null) (kotlin.math.sin(shake / 550.0 * Math.PI * 4) * 5 * (1 - shake / 550.0)).toFloat() else 0f
        val cx = x + dx

        val name = Ui.fit(Type.name, GuiData.name(m), w - 15f - 17f - 16f)
        Ui.text(Type.name, name, cx + 7.5f, yy + 11.5f, if (on) Tok.WHITE else 0xE6F5F5F7.toInt())
        Ui.switch("card:" + m.id, cx + w - 24.5f, yy + 6.5f, on) { m.toggle() }
        // the star follows the name (the mockup's .top row, gap 8px)
        val fav = GuiData.isFav(m)
        val stx = cx + 7.5f + Type.name.width(name) + 4f
        val starOver = Ui.hit("star:" + m.id, stx, yy + 7.5f, 8f, 8f, { if (it == Keys.MOUSE_LEFT) GuiData.toggleFav(m) })
        if (over || starOver || fav) Ui.icon(if (fav) "favon" else "fav", stx, yy + 7.5f, 8f,
            if (fav) 0xFFFFC94D.toInt() else if (starOver) Tok.T2 else Tok.T4)
        val desc = GuiData.description(m)
        val lines = Ui.wrap(Type.desc, desc, w - 15f, 2)
        for ((i, l) in lines.withIndex()) Ui.text(Type.desc, l, cx + 7.5f, yy + 22.2f + i * 8.4f, if (over || on) Tok.T2 else Tok.T3)

        val alt = Keys.down(Keys.LALT)
        val footAlpha = Ui.anim("alt", if (alt) 0.15f else 1f, 250)
        // the foot row, centred 41.25 down (chips 9.5 tall, keycaps 9, the gear 11)
        val fy = yy + 36.5f
        val pa = Canvas.alpha
        Canvas.alpha = pa * footAlpha
        var fx = cx + 7.5f
        if (capturing) {
            Ui.text(Type.chip, "Press a key or mouse button...", fx, fy + 4.5f, Tok.light(Tok.a1, 0.35f))
        } else {
            if (showSub) {
                val s = subName(m.subCategory)
                val sw = Type.chip.width(s) + 15.5f
                Ui.box(fx, fy, sw, 9.5f, 3f, Tok.FILL, 0.5f, Tok.LINE)
                Ui.icon(m.subCategory.id, fx + 3f, fy + 2f, 5.5f, Tok.T3)
                Ui.text(Type.chip, s, fx + 11f, fy + 4.75f, Tok.T3)
                fx += sw + 3f
            }
            val b = m.bind
            if (b.key != -1) fx += Ui.keycap(fx, fy + 0.25f, Keys.name(b) + if (b.hold) " · hold" else "") + 3f
            val md = GuiData.mode(m)
            if (md.isNotEmpty()) {
                val mw = Type.chip.width(md) + 8f
                Ui.box(fx, fy, mw, 9.5f, 3f, Tok.alpha(Tok.a1, 0.12f), 0.5f, Tok.alpha(Tok.a1, 0.22f))
                Ui.textC(Type.chip, md, fx + mw / 2f, fy + 4.75f, Tok.light(Tok.a1, 0.3f))
            }
        }
        // the gear: settings count, opens the inspector
        val n = m.settings.count { GuiData.valued(it) && it.id !in Module.META }
        val gw = Type.chip.width("$n") + 14.5f
        val gx = cx + w - 7.5f - gw
        val gOver = Ui.hit("gear:" + m.id, gx, fy - 0.75f, gw, 11f, { if (it == Keys.MOUSE_LEFT) select(m) })
        if (gOver || selected === m) Ui.box(gx, fy - 0.75f, gw, 11f, 3.5f, Tok.FILL2)
        Ui.icon("gear", gx + 3f, fy + 1.5f, 6.5f, if (gOver || selected === m) Tok.T1 else Tok.T3)
        Ui.text(Type.chip, "$n", gx + 11.5f, fy + 4.75f, if (gOver || selected === m) Tok.T1 else Tok.T3)
        Canvas.alpha = pa
        if (alt || footAlpha < 0.99f) {
            Canvas.alpha = pa * (1f - (footAlpha - 0.15f) / 0.85f)
            val b = m.bind
            val label = if (b.key == -1) "No bind" else Keys.name(b)
            val kw = Type.chip.width(label) + 8f
            Ui.keycap(cx + w - 5f - kw, yy + h - 17f, label, none = b.key == -1, h = 12f)
            Canvas.alpha = pa
        }
        Canvas.alpha = prevAlpha
    }

    private fun cardClick(m: Module, button: Int) {
        when {
            button == Keys.MOUSE_LEFT && Keys.down(Keys.LCONTROL) && Keys.down(Keys.LSHIFT) -> {
                // Reset: off, every setting (bind included) back to its default
                if (m.enabled) m.disable()
                m.settings.forEach { it.reset() }
            }
            button == Keys.MOUSE_LEFT && Keys.down(Keys.LALT) -> startCapture(m, m.bindSetting)
            button == Keys.MOUSE_LEFT -> m.toggle()
            button == Keys.MOUSE_RIGHT -> select(m)
            button == Keys.MOUSE_MIDDLE -> startCapture(m, m.bindSetting)
        }
    }

    fun select(m: Module?) {
        if (m == null) {
            if (selected != null) Sounds.SETTINGS_CLOSE.play()
            selected = null
            return
        }
        if (selected === m) {
            shakes[m] = System.currentTimeMillis()
            return
        }
        selected = m
        Sounds.SETTINGS_OPEN.play()
        Inspector.opened(m)
    }

    fun startCapture(m: Module, s: BindSetting) {
        capture = Capture(m, s)
    }

    private fun finishCapture(b: Bind) {
        val c = capture ?: return
        capture = null
        c.setting.value = b
    }

    // ---- status bar ----

    private fun statusBar() {
        val y = H - STATUS
        Ui.box(0f, y, W, 0.5f, 0f, Tok.LINE)
        val cy = y + 0.25f + STATUS / 2f
        Ui.text(Type.status, "Horizon · Minecraft 26.3", 9.5f, cy, Tok.T3)
        val hints = listOf("LMB" to "toggle", "RMB" to "settings", "MMB" to "bind", "Alt" to "binds", "Ctrl F" to "search")
        var hw = 0f
        for ((k, t) in hints) hw += Type.ver.width(k) + 6f + 2.5f + Type.status.width(t) + 7f
        hw -= 7f
        var hx = (W - hw) / 2f
        for ((key, t) in hints) {
            hx += statusKey(hx, cy - 4f, key) + 2.5f
            Ui.text(Type.status, t, hx, cy, Tok.T3)
            hx += Type.status.width(t) + 7f
        }
        val all = shown()
        val txt = "${all.count { it.enabled }} enabled · ${all.count { it.bind.key != -1 }} binds · ${all.size} modules"
        val tx = W - 9.5f - Type.status.width(txt)
        Ui.textR(Type.status, txt, W - 9.5f, cy, Tok.T3)
        Ui.glow(tx - 6f, cy - 1.5f, 3f, 3f, 1.5f, 3f, 0x8034D77B.toInt())
        Ui.box(tx - 6f, cy - 1.5f, 3f, 3f, 1.5f, 0xFF34D77B.toInt())
    }

    /** The status bar's keycaps: 8 tall, 10px text. */
    private fun statusKey(x: Float, y: Float, text: String): Float {
        val w = Type.ver.width(text) + 6f
        Ui.box(x, y, w, 8f, 2.5f, 0x0FFFFFFF, 0.5f, 0x1AFFFFFF)
        Ui.box(x + 0.5f, y + 7f, w - 1f, 0.5f, 0f, 0x1AFFFFFF)
        Ui.textC(Type.ver, text, x + w / 2f, y + 3.75f, Tok.T2)
        return w
    }

    // ---- search ----

    private val RU = "йцукенгшщзхъфывапролджэячсмитьбю"
    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,."

    private fun score(text: String, q: String): Int {
        val t = text.lowercase(Locale.ROOT)
        return when {
            t.startsWith(q) -> 3
            t.split(' ', '-').any { it.startsWith(q) } -> 2
            q in t -> 1
            else -> 0
        }
    }

    private fun runSearch() {
        var q = search.text.lowercase(Locale.ROOT).trim()
        q = q.map { if (it in RU) EN[RU.indexOf(it)] else it }.joinToString("")
        if (q.isEmpty()) {
            hits = emptyList()
            return
        }
        val mods = ArrayList<Pair<SearchHit, Int>>()
        val sets = ArrayList<Pair<SearchHit, Int>>()
        for (m in shown()) {
            val sc = maxOf(score(GuiData.name(m), q) * 3, score(m.id, q) * 3, m.aliases.maxOfOrNull { score(it, q) * 2 } ?: 0,
                if (q in GuiData.description(m).lowercase(Locale.ROOT)) 1 else 0)
            if (sc > 0) mods += SearchHit(m, null) to sc
            for (s in m.settings) {
                if (!GuiData.valued(s) || s.id in Module.META) continue
                val ss = max(score(GuiData.name(s), q), score(s.id, q))
                if (ss > 0) sets += SearchHit(m, s) to ss
            }
        }
        hits = mods.sortedWith(compareBy({ -it.second }, { GuiData.name(it.first.module) })).take(6).map { it.first } +
            sets.sortedWith(compareBy({ -it.second }, { GuiData.name(it.first.module) })).take(8).map { it.first }
        cur = 0
    }

    private fun searchResults() {
        if (Ui.focused !== search || hits.isEmpty() && search.text.isEmpty()) return
        val sx = searchX + (W - INSP - 30f - searchX - SEARCH_W) / 2f + 20f
        val y0 = BAR - 2f
        var h = 6f
        var lastKind = -1
        for (hit in hits) {
            val kind = if (hit.setting == null) 0 else 1
            if (kind != lastKind) { h += 11f; lastKind = kind }
            h += 17f
        }
        if (hits.isEmpty()) h = 24f
        h = min(h, 235f)
        Ui.blur(sx, y0, SEARCH_W, h, 7f)
        Ui.box(sx, y0, SEARCH_W, h, 7f, Colors.withAlpha(0xFF16141C.toInt(), ui.opacity.value + 0.12f), 0.5f, Tok.LINE2)
        Ui.block("results", sx, y0, SEARCH_W, h)
        if (hits.isEmpty()) {
            Ui.textC(Type.small, "No module or setting matches \"${search.text}\"", sx + SEARCH_W / 2f, y0 + 12f, Tok.T3)
            return
        }
        var y = y0 + 3f
        lastKind = -1
        val q = search.text.lowercase(Locale.ROOT)
        for ((i, hit) in hits.withIndex()) {
            val kind = if (hit.setting == null) 0 else 1
            if (kind != lastKind) {
                Ui.text(Type.section, if (kind == 0) "MODULES" else "SETTINGS", sx + 8f, y + 6f, Tok.T4)
                y += 11f
                lastKind = kind
            }
            if (y + 17f > y0 + h) break
            val over = Ui.hit("res:$i", sx + 3f, y, SEARCH_W - 6f, 16f, { if (it == Keys.MOUSE_LEFT) activate(hit) })
            if (over && cur != i) cur = i
            if (i == cur) Ui.box(sx + 3f, y, SEARCH_W - 6f, 16f, 4.5f, Colors.lerp(0x0AFFFFFF, Tok.alpha(Tok.a1, 0.16f), 0.8f))
            val m = hit.module
            Ui.box(sx + 8f, y + 6.5f, 3.5f, 3.5f, 1.75f, if (hit.setting == null && m.enabled) Tok.a1 else 0x2EFFFFFF)
            val name = if (hit.setting == null) GuiData.name(m) else GuiData.name(m) + " › " + GuiData.name(hit.setting)
            Ui.text(Type.semi, Ui.fit(Type.semi, name, SEARCH_W - 90f), sx + 16f, y + 5.5f, Tok.T1)
            val sub = if (hit.setting == null) GuiData.description(m) else Inspector.valueText(hit.setting)
            Ui.text(Type.tiny, Ui.fit(Type.tiny, sub, SEARCH_W - 90f), sx + 16f, y + 11.5f, Tok.T3)
            val where = if (hit.setting == null) Categories.ALL.first { m.subCategory in it.subCategories }.displayName.let { if (it == "Pvp") "PvP" else it } +
                " › " + subName(m.subCategory) else hit.setting.javaClass.simpleName.removeSuffix("Setting").lowercase(Locale.ROOT)
            Ui.textR(Type.chip, where, sx + SEARCH_W - 10f, y + 8f, Tok.T3)
            y += 17f
        }
        if (q.isEmpty()) return
    }

    private fun activate(hit: SearchHit) {
        search.text = ""
        hits = emptyList()
        Ui.focused = null
        jumpTo(hit.module, hit.setting)
    }

    /** Show the module's tab and select it (it wiggles); a setting opens the module's settings at it. */
    fun jumpTo(m: Module, s: Setting<*>? = null) {
        go(View.Sub(m.subCategory))
        if (filter != Filter.ALL) {
            filter = Filter.ALL
            enterView(0f)
        }
        val list = mods(view)
        val i = list.indexOf(m)
        if (i >= 0) gridScroll = max(0f, (i / 2) * 57f - 60f)
        if (selected !== m) select(m)
        shakes[m] = System.currentTimeMillis() + 150
        if (s != null) Inspector.reveal(s)
    }

    // ---- input ----

    private fun toWindow(x: Double, y: Double) {
        Ui.mx = ((x - ox) / k).toFloat()
        Ui.my = ((y - oy) / k).toFloat()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        toWindow(event.x(), event.y())
        val b = event.button()
        if (capture != null) {
            if (System.currentTimeMillis() - capture!!.started > 80) finishCapture(Bind(b, capture!!.setting.value.hold, true))
            return true
        }
        val pop = popover
        if (pop != null && !pop.contains(Ui.mx, Ui.my) && !pop.anchorContains(Ui.mx, Ui.my)) popover = null
        val hadFocus = Ui.focused
        val id = Ui.press(b)
        // a click anywhere but the focused field (or the search's results) ends its editing
        if (hadFocus != null && Ui.focused === hadFocus) {
            val keep = id == Ui.fieldId(hadFocus) || (hadFocus === search && (id == "results" || id?.startsWith("res:") == true))
            if (!keep) {
                Inspector.commitField(hadFocus)
                Ui.focused = null
            }
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        toWindow(event.x(), event.y())
        Ui.release()
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        toWindow(event.x(), event.y())
        Ui.drag()
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        toWindow(x, y)
        Ui.scroll(scrollY)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val c = capture
        if (c != null) {
            finishCapture(if (key == Keys.ESCAPE || key == Keys.DELETE || key == Keys.BACKSPACE) Bind(-1, c.setting.value.hold, false)
                else Bind(key, c.setting.value.hold, false))
            return true
        }
        val f = Ui.focused
        if (key == Keys.ESCAPE) {
            when {
                popover != null -> popover = null
                f === search && search.text.isNotEmpty() -> { search.text = ""; hits = emptyList() }
                f != null -> { Inspector.commitField(f); Ui.focused = null }
                else -> onClose()
            }
            return true
        }
        if (f === search) {
            when (key) {
                Keys.DOWN -> { cur = min(hits.size - 1, cur + 1); return true }
                Keys.UP -> { cur = max(0, cur - 1); return true }
                Keys.ENTER -> { hits.getOrNull(cur)?.let { activate(it) }; return true }
            }
        }
        if (f != null) {
            if (key == Keys.ENTER || key == Keys.TAB) {
                if (key == Keys.TAB && Popovers.complete(f)) return true
                Inspector.commitField(f)
                Ui.focused = null
                return true
            }
            if (f.key(key)) return true
            return true
        }
        if (key == Keys.F && Keys.down(Keys.LCONTROL)) {
            Ui.focused = search
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
        if (capture != null) return true
        val s = event.codepointAsString()
        val f = Ui.focused
        if (f != null) {
            s.forEach { f.type(it) }
            return true
        }
        if (s.isNotBlank() && !Keys.down(Keys.LALT)) {
            Ui.focused = search
            s.forEach { search.type(it) }
        }
        return true
    }
}
