package dev.horizon.client.gui.modern

import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
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
import dev.horizon.client.setting.EnumSetting
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
 * The ClickGUI. One window of neutral glass over the blurred world: a sidebar (the categories' tabs, the two
 * collections, the config), a top bar (search, and the tools that open the other screens), the open tab's modules as
 * tiles, and an inspector with the selected module's settings. Controls: left click a tile toggles it, right click
 * (or its gear) opens its settings, middle click binds it, Ctrl+Shift+click resets it, Alt shows every bind; typing
 * anywhere searches.
 */
object ModernGui : Screen(Component.literal("horizon-modern")) {
    // the window, in units
    const val W = 620f
    const val H = 350f
    private const val BAR = 28f
    private const val SIDE = 108f
    private const val INSP = 188f

    private val mc get() = Minecraft.getInstance()
    private val ui get() = Modules.get<ClickGuiModule>()

    sealed class View(val key: String) {
        val navId = "nav:$key"

        class Sub(val sub: SubCategory) : View("sub:" + sub.id)
        object Active : View("active")
        object Fav : View("fav")
    }

    enum class Filter { ALL, ON, OFF }

    /** One view per tab, kept: the sidebar asks for them every frame. */
    private val subViews = HashMap<SubCategory, View.Sub>()
    private fun viewOf(s: SubCategory): View.Sub = subViews.getOrPut(s) { View.Sub(s) }

    var view: View = viewOf(Categories.ATTACK)
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
        val s = k * (0.975f + 0.025f * t)
        pose.translate(ox + W * k / 2f, oy + H * k / 2f + (1f - t) * 5f)
        pose.scale(s, s)
        pose.translate(-W / 2f, -H / 2f)
        window()
        pose.popMatrix()
        Canvas.alpha = 1f
    }

    /** The window's tint at a height (its glass: [window]). */
    private fun tintAt(y: Float): Int {
        val g = ui.opacity.value
        return Ui.vboxAt(0f, H, Colors.withAlpha(Tok.GLASS_TOP, g), Colors.withAlpha(Tok.GLASS_BOTTOM, g + 0.06f), y)
    }

    /** The window's background, for fading the ends of its scrolling lists ([Ui.fade]). */
    internal val backdrop = Ui.Backdrop(null, { y -> tintAt(y) })

    private fun window() {
        Ui.glow(15f, 40f, W - 30f, H - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(-0.5f, -0.5f, W + 1f, H + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(0f, 0f, W, H, 11f)
        val g = ui.opacity.value
        Ui.vbox(0f, 0f, W, H, 11f, Colors.withAlpha(Tok.GLASS_TOP, g), Colors.withAlpha(Tok.GLASS_BOTTOM, g + 0.06f), 0.5f, 0x17FFFFFF)
        Ui.insetTop(0f, 0f, W, 11f, 0x12FFFFFF)
        Ui.block("window", 0f, 0f, W, H)
        tally()
        sidebar()
        topBar()
        main()
        Inspector.draw(W - INSP, BAR, INSP, H - BAR)
        searchResults()
        popover?.let {
            if (!it.draw()) popover = null
        }
    }

    // ---- what a frame reads about the modules, counted once ----

    private class Tally {
        var on = 0
        var total = 0
    }

    private val tallies = HashMap<SubCategory, Tally>()
    private var enabledTotal = 0
    private var favTotal = 0

    /** Changes when a module is turned on or off, starred or hidden: the lists are built again then, not every frame. */
    private var stamp = 0

    private fun tally() {
        for (t in tallies.values) {
            t.on = 0
            t.total = 0
        }
        enabledTotal = 0
        favTotal = 0
        val ghost = GhostMode.enabled
        var h = if (ghost) 1 else 0
        val all = Modules.all
        for (i in all.indices) {
            val m = all[i]
            if (ghost && GhostMode.blocks(m)) continue
            val t = tallies.getOrPut(m.subCategory) { Tally() }
            t.total++
            if (m.enabled) {
                t.on++
                enabledTotal++
                h = h * 31 + i + 1
            }
            if (GuiData.isFav(m)) {
                favTotal++
                h = h * 37 + i + 1
            }
        }
        stamp = h
    }

    private fun count(v: View): Int = when (v) {
        is View.Sub -> tallies[v.sub]?.on ?: 0
        View.Active -> enabledTotal
        View.Fav -> favTotal
    }

    private val numbers = Array(256) { it.toString() }
    private fun number(n: Int) = if (n in numbers.indices) numbers[n] else n.toString()

    /** What a tile shows and asks [Ui] for every frame, kept by module rather than put together again each time. */
    private class Tile(m: Module) {
        val id = "card:" + m.id
        val on = "on:" + m.id
        val hover = "hover:" + m.id
        val gear = "gear:" + m.id
        val name = GuiData.name(m)
        val description = GuiData.description(m)
        private val modeSetting = m.settings.firstOrNull { it.id == "mode" } as? EnumSetting<*>
        private var modeValue: Any? = null
        private var modeText = ""

        /** The module's mode as the Array List shows it, or "". */
        fun mode(): String {
            val v = modeSetting?.value ?: return ""
            if (v !== modeValue) {
                modeValue = v
                modeText = GuiData.enumName(v)
            }
            return modeText
        }
    }

    private val tiles = HashMap<Module, Tile>()

    // ---- sidebar ----

    private fun navKeys(): List<View> =
        Categories.ALL.flatMap { c -> c.subCategories.filter { shows(it) }.map { viewOf(it) } } + listOf(View.Active, View.Fav)

    /**
     * The modules the GUI shows. With Ghost Mode on, the blatant ones it blocks are hidden (and a
     * tab left with none, Rage, with them).
     */
    fun shown(): List<Module> = if (GhostMode.enabled) Modules.all.filter { !GhostMode.blocks(it) } else Modules.all

    private fun shows(s: SubCategory) = !GhostMode.enabled || shown().any { it.subCategory == s }

    private fun subName(s: SubCategory) = if (s.id == "esp") "ESP" else s.displayName

    private val catNames = HashMap<SubCategory, String>()
    private fun catName(s: SubCategory) = catNames.getOrPut(s) {
        Categories.ALL.first { s in it.subCategories }.displayName.let { if (it == "Pvp") "PvP" else it }
    }

    private const val NAV_TOP = 28f
    private const val NAV_ROW = 16f
    private const val NAV_LABEL = 15f
    private const val NAV_X = 6f
    private const val NAV_W = SIDE - 12f
    private const val CONFIG_Y = H - 7f - 20f

    /** How far from their ends the sidebar's list and the module grid fade out ([Ui.fade]). */
    private const val NAV_FADE = 8f
    private const val GRID_FADE = 10f

    private var navScroll = 0f
    private var navScrollMax = 0f
    private val navLabels = Categories.ALL.map { it.displayName.uppercase(Locale.ROOT) }

    /** The brand, the tabs under their categories (they fit; they scroll if a window is short of room), the config. */
    private fun sidebar() {
        // Ghost Mode turned on with a hidden tab or module open: move off it.
        (view as? View.Sub)?.let { v -> if ((tallies[v.sub]?.total ?: 0) == 0) navKeys().firstOrNull()?.let { view = it; enterView(0f) } }
        selected?.let { m -> if (GhostMode.blocks(m)) selected = mods(view).firstOrNull() ?: shown().firstOrNull() }
        Ui.box(SIDE, 0f, 0.5f, H, 0f, Tok.LINE)
        Ui.mark(10f, 7.5f, 13f)
        Ui.text(Type.brand, "Horizon", 27.5f, 14f, Tok.T1)

        val top = NAV_TOP
        val bottom = CONFIG_Y - 4f
        val sy = Ui.anim("navScroll", navScroll, 300)
        // where the selected tab is, and how tall the list is
        var y = 0f
        var sel = -1f
        for (c in Categories.ALL) {
            y += NAV_LABEL
            for (s in c.subCategories) if ((tallies[s]?.total ?: 0) > 0) {
                if (viewOf(s).key == view.key) sel = y
                y += NAV_ROW
            }
        }
        y += NAV_LABEL
        if (view === View.Active) sel = y
        y += NAV_ROW
        if (view === View.Fav) sel = y
        y += NAV_ROW
        navScrollMax = max(0f, y + 2f - (bottom - top))
        navScroll = navScroll.coerceIn(0f, navScrollMax)
        Ui.hit("nav", 0f, top, SIDE, bottom - top, scroll = { a -> navScroll = (navScroll - a.toFloat() * NAV_ROW).coerceIn(0f, navScrollMax); true }, pointer = false)
        Ui.pushClip(0f, top, SIDE, bottom - top)
        // the pill under the selected tab
        if (sel >= 0f) {
            val py = top + Ui.anim("pill", sel, 420) - sy
            Ui.hbox(NAV_X, py, NAV_W, NAV_ROW, 5f, 0x1AFFFFFF, 0x0DFFFFFF, 0.5f, 0x14FFFFFF)
        }
        y = top - sy
        for ((i, c) in Categories.ALL.withIndex()) {
            Ui.text(Type.section, navLabels[i], 11.5f, y + 9.5f, Tok.T4)
            y += NAV_LABEL
            for (s in c.subCategories) if ((tallies[s]?.total ?: 0) > 0) {
                navItem(viewOf(s), subName(s), s.id, y)
                y += NAV_ROW
            }
        }
        Ui.text(Type.section, "COLLECTIONS", 11.5f, y + 9.5f, Tok.T4)
        y += NAV_LABEL
        navItem(View.Active, "Enabled", "active", y)
        y += NAV_ROW
        navItem(View.Fav, "Favourites", "fav", y)
        Ui.popClip()
        // the list's ends fade out while there is more that way
        Ui.fade(backdrop, 1f, top, SIDE - 1f, NAV_FADE, false, sy / NAV_FADE)
        Ui.fade(backdrop, 1f, bottom, SIDE - 1f, NAV_FADE, true, (navScrollMax - sy) / NAV_FADE)
        config()
    }

    private fun navItem(v: View, label: String, icon: String, y: Float) {
        val active = v.key == view.key
        val over = Ui.hit(v.navId, NAV_X, y, NAV_W, NAV_ROW, { if (it == Keys.MOUSE_LEFT) go(v) })
        if (over && !active) Ui.box(NAV_X, y, NAV_W, NAV_ROW, 5f, Tok.FILL)
        val c = if (active) Tok.WHITE else if (over) Tok.T1 else Tok.T2
        Ui.icon(icon, 11.5f, y + 4f, 8f, c)
        Ui.text(Type.nav, label, 24.5f, y + 8f, c)
        // how many are on (a collection: how many it holds)
        val n = count(v)
        if (n > 0) Ui.textR(Type.chip, number(n), NAV_X + NAV_W - 6f, y + 8f, if (active) Tok.T2 else Tok.T3)
    }

    /** For ShotBot: opens the config menu (configs, public configs, Ghost Mode...). */
    @JvmStatic
    fun testProfile() {
        popover = ProfileMenu(NAV_X, CONFIG_Y)
    }

    /** The module config in use; a click lists the others. */
    private fun config() {
        val y = CONFIG_Y
        val open = popover is ProfileMenu
        val over = Ui.hit("profile", NAV_X, y, NAV_W, 20f, { if (it == Keys.MOUSE_LEFT) popover = if (popover is ProfileMenu) null else ProfileMenu(NAV_X, y) })
        val lit = over || open
        Ui.box(NAV_X, y, NAV_W, 20f, 5f, if (lit) Tok.FILL2 else Tok.FILL, 0.5f, if (lit) Tok.LINE2 else Tok.LINE)
        Ui.icon("config", 11.5f, y + 6f, 8f, if (lit) Tok.T1 else Tok.T2)
        val cfg = Configs.of(ConfigType.MODULES).current.id
        Ui.text(Type.label, Ui.fit(Type.label, cfg, NAV_W - 18.5f - 14f), 24.5f, y + 10f, Tok.T1)
        Ui.icon("updown", NAV_X + NAV_W - 12.5f, y + 6f, 8f, Tok.T3)
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

    // ---- top bar: search and the tools ----

    private const val SEARCH_X = SIDE + 12f
    private const val SEARCH_W = W - SIDE - INSP - 24f
    private const val SEARCH_Y = 5.5f
    private const val SEARCH_H = 17f
    private val tools = listOf("Lunar" to "lunar", "Windows" to "windows", "HUD" to "layout", "Theme" to "theme")
    private fun toolW(label: String) = Type.sub.width(label) + 22.5f

    private fun topBar() {
        Ui.box(SIDE + 0.5f, BAR, W - SIDE - 0.5f, 0.5f, 0f, Tok.LINE)
        val cy = SEARCH_Y + SEARCH_H / 2f

        // the search field, over the modules
        val sx = SEARCH_X
        val focusedNow = Ui.focused === search
        val over = Ui.hit(Ui.fieldId(search), sx, SEARCH_Y, SEARCH_W, SEARCH_H, { if (it == Keys.MOUSE_LEFT) Ui.focused = search })
        Ui.box(sx, SEARCH_Y, SEARCH_W, SEARCH_H, 5f, if (focusedNow) Tok.FILL2 else Tok.FILL, 0.5f,
            if (focusedNow) 0x52FFFFFF else if (over) Tok.LINE2 else Tok.LINE)
        Ui.icon("search", sx + 6f, cy - 4f, 8f, if (focusedNow) Tok.T2 else Tok.T3)
        Ui.fieldText(search, sx + 19f, cy, SEARCH_W - 54f, Type.label, "Search modules and settings")
        if (!focusedNow) Ui.keycap(sx + SEARCH_W - 4.5f - Type.chip.width("Ctrl F") - 6f, cy - 4.5f, "Ctrl F")

        // tools, right to left, over the inspector
        var x = W - 8f
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
            val o = Ui.hit("tool:$label", x, SEARCH_Y, w, SEARCH_H, { if (it == Keys.MOUSE_LEFT) run() })
            val on = (label == "Theme" && popover is ThemeMenu) || (label == "Lunar" && popover is LunarMenu)
            if (o || on) Ui.box(x, SEARCH_Y, w, SEARCH_H, 5f, Tok.FILL2)
            // Lunar's own logo for its menu.
            if (icon == "lunar") dev.horizon.client.lunar.LunarNameTag.canvasLogo(x + 5f, cy - 4.5f, 9f, if (o || on) Tok.T1 else Tok.T2, 0)
            else Ui.icon(icon, x + 5.5f, cy - 4f, 8f, if (o || on) Tok.T1 else Tok.T2)
            Ui.text(Type.sub, label, x + 17f, cy, if (o || on) Tok.T1 else Tok.T2)
            x -= 2f
        }
    }

    private val toolX = HashMap<String, Float>()
    private fun x0(label: String) = (toolX[label] ?: (W - 60f)) + toolW(label) - 150f

    // ---- main: header and the tiles ----

    private const val TILE_H = 29f
    private const val TILE_GAP = 4f
    private const val GRID_Y = 66f

    // the open tab's modules, built again only when they change
    private var listKey = ""
    private var listStamp = -1
    private var listFilter = Filter.ALL
    private var listAll: List<Module> = emptyList()
    private var list: List<Module> = emptyList()
    private var heading = ""
    private var subPrefix = ""
    private var subOn = ""

    private fun refreshList() {
        val v = view
        if (v.key == listKey && stamp == listStamp && filter == listFilter) return
        listKey = v.key
        listStamp = stamp
        listFilter = filter
        listAll = mods(v)
        list = if (filter == Filter.ALL) listAll else listAll.filter { (filter == Filter.ON) == it.enabled }
        heading = when (v) {
            is View.Sub -> subName(v.sub)
            View.Active -> "Enabled"
            View.Fav -> "Favourites"
        }
        val n = listAll.size
        val on = listAll.count { it.enabled }
        val modules = "$n module${if (n == 1) "" else "s"}"
        subPrefix = (if (v is View.Sub) catName(v.sub) + " · " else "") + if (v === View.Active) modules else "$modules · "
        subOn = if (v === View.Active) "" else "$on enabled"
    }

    private val filterLabels = listOf("All", "Enabled", "Disabled")

    private fun main() {
        val x0 = SIDE
        val w = W - SIDE - INSP
        val v = view
        refreshList()
        val left = x0 + 12f
        Ui.text(Type.title, heading, left, 43.5f, Tok.T1)
        Ui.text(Type.sub, subPrefix, left, 56f, Tok.T3)
        if (subOn.isNotEmpty()) Ui.text(Type.sub, subOn, left + Type.sub.width(subPrefix), 56f, Tok.T2)
        val fw = Ui.segWidth(filterLabels)
        Ui.seg("filter", x0 + w - 12f - fw, 42f, fw, 15f, filterLabels, filter.ordinal, plain = true, natural = true) {
            filter = Filter.entries[it]
            Sounds.SWITCH_RIGHT.play()
            enterView(0f)
        }

        val gy = GRID_Y
        val gh = H - gy
        val pitch = TILE_H + TILE_GAP
        val rows = (list.size + 1) / 2
        gridScrollMax = max(0f, 2f + rows * pitch - TILE_GAP + 12f - gh)
        val tileW = (w - 24f - TILE_GAP) / 2f
        gridScroll = gridScroll.coerceIn(0f, gridScrollMax)
        val sy = Ui.anim("gridScroll", gridScroll, 300)
        Ui.hit("grid", x0, gy, w, gh, scroll = { a -> gridScroll = (gridScroll - a.toFloat() * pitch).coerceIn(0f, gridScrollMax); true }, pointer = false)
        Ui.pushClip(x0, gy, w, gh)
        if (list.isEmpty()) {
            val text = when {
                v === View.Fav && listAll.isEmpty() -> "Star a module in its settings to keep it here."
                v === View.Active && listAll.isEmpty() -> "Nothing is on."
                else -> "Nothing here."
            }
            Ui.textC(Type.label, text, x0 + w / 2f, gy + 40f, Tok.T3)
        }
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val cx = left + (i % 2) * (tileW + TILE_GAP)
            val cy = gy + 2f + (i / 2) * pitch - sy
            if (cy + TILE_H < gy - 2f || cy > gy + gh + 2f) continue
            val p = ((now - viewChanged - min(i, 24) * 10L) / 420.0).coerceIn(0.0, 1.0)
            val e = if (p >= 1.0) 1f else Easing.OUT_EXPO.apply(p).toFloat()
            tile(list[i], cx + viewDir * 8f * (1f - e), cy + (if (viewDir == 0f) 2f * (1f - e) else 0f), tileW, e, v !is View.Sub)
        }
        Ui.popClip()
        Ui.fade(backdrop, x0 + 1f, gy, w - 1f, GRID_FADE, false, sy / GRID_FADE)
        Ui.fade(backdrop, x0 + 1f, gy + gh, w - 1f, GRID_FADE, true, (gridScrollMax - sy) / GRID_FADE)
        if (gridScrollMax > 0f) scrollbar(x0 + w - 4.5f, gy + 2f, gh - 10f, sy, gridScrollMax)
    }

    fun scrollbar(x: Float, y: Float, h: Float, scroll: Float, max: Float) {
        val th = max(16f, h * h / (h + max))
        Ui.box(x, y + (h - th) * (scroll / max), 2f, th, 1f, 0x1AFFFFFF)
    }

    /**
     * A module: its name (then its mode, or its tab in a collection, and its bind) with its switch at the end of the
     * line, and under them a line of what it does. It is a shade lighter while on; the selected one has a white
     * hairline; the gear shows under the pointer.
     */
    private fun tile(m: Module, x: Float, y: Float, w: Float, appear: Float, collection: Boolean) {
        val id = tiles.getOrPut(m) { Tile(m) }
        val prevAlpha = Canvas.alpha
        Canvas.alpha = prevAlpha * appear
        val on = m.enabled
        val sel = selected === m
        val capturing = capture?.let { it.module === m && it.setting === m.bindSetting } == true
        val over = Ui.hit(id.id, x, y, w, TILE_H, { b -> tileClick(m, b) })
        val t = Ui.anim(id.on, if (on) 1f else 0f, 260)
        val h = Ui.anim(id.hover, if (over) 1f else 0f, 160)
        val border = when {
            capturing -> Colors.withAlpha(-1, 0.4f + 0.35f * (0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 190.0).toFloat()))
            sel -> 0x70FFFFFF
            else -> Colors.withAlpha(-1, 0.05f + 0.06f * t + 0.04f * h)
        }
        Ui.box(x, y, w, TILE_H, 6f, Colors.withAlpha(-1, 0.03f + 0.045f * t + 0.03f * h), 0.5f, border)
        // a wiggle when jumped to
        val shake = shakes[m]?.let { System.currentTimeMillis() - it }?.takeIf { it < 550 }
        val dx = if (shake != null) (kotlin.math.sin(shake / 550.0 * Math.PI * 4) * 4 * (1 - shake / 550.0)).toFloat() else 0f
        val cx = x + dx + 8f

        // the switch, and the gear beside it under the pointer (and on the selected one)
        val swX = x + dx + w - 8f - 17f
        val ny = y + 9.5f
        Ui.switch(id.id, swX, ny - 5f, on) { m.toggle() }
        val gx = swX - 4f - 11f
        val gearOver = Ui.hit(id.gear, gx, ny - 5.5f, 11f, 11f, { if (it == Keys.MOUSE_LEFT) select(m) })
        if (over || gearOver || sel) {
            if (gearOver) Ui.box(gx, ny - 5.5f, 11f, 11f, 3.5f, Tok.FILL2)
            Ui.icon("gear", gx + 2f, ny - 3.5f, 7f, if (gearOver || sel) Tok.T1 else Tok.T3)
        }
        val textRight = gx - 4f

        // the name's line
        val name = Ui.fit(Type.name, id.name, textRight - cx)
        Ui.text(Type.name, name, cx, ny, Colors.lerp(Tok.T2, Tok.WHITE, max(t, h * 0.6f)))
        var nx = cx + Type.name.width(name) + 4f
        if (GuiData.isFav(m) && nx + 7f <= textRight) {
            Ui.icon("favon", nx, ny - 3.5f, 6.5f, Tok.T2)
            nx += 9f
        }
        val tag = if (collection) subName(m.subCategory) else id.mode()
        if (tag.isNotEmpty() && nx + Type.tiny.width(tag) <= textRight) {
            Ui.text(Type.tiny, tag, nx, ny + 0.25f, Tok.T3)
            nx += Type.tiny.width(tag) + 4f
        }
        val b = m.bind
        val alt = Keys.down(Keys.LALT)
        if (b.key != -1 || alt) {
            val key = if (b.key == -1) "None" else if (b.hold) Keys.name(b) + " · hold" else Keys.name(b)
            if (nx + Type.chip.width(key) + 6f <= textRight) Ui.keycap(nx, ny - 4f, key, none = b.key == -1, h = 8f)
        }

        // what it does, or what a bind is waiting for
        val line = if (capturing) "Press a key or mouse button..." else id.description
        Ui.text(Type.desc, Ui.fit(Type.desc, line, x + dx + w - 8f - cx), cx, y + 20f, if (capturing) Tok.T1 else Colors.lerp(Tok.T3, Tok.T2, h))
        Canvas.alpha = prevAlpha
    }

    private fun tileClick(m: Module, button: Int) {
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
            sets.sortedWith(compareBy({ -it.second }, { GuiData.name(it.first.module) })).take(7).map { it.first }
        cur = 0
    }

    private const val RES_ROW = 20f
    private const val RES_LABEL = 12f

    /** The matches, in a card hanging from the search field: modules, then settings. */
    private fun searchResults() {
        if (Ui.focused !== search || hits.isEmpty() && search.text.isEmpty()) return
        val sx = SEARCH_X
        val sw = SEARCH_W
        val y0 = SEARCH_Y + SEARCH_H + 3f
        var h = 6f
        var lastKind = -1
        for (hit in hits) {
            val kind = if (hit.setting == null) 0 else 1
            if (kind != lastKind) { h += RES_LABEL; lastKind = kind }
            h += RES_ROW
        }
        if (hits.isEmpty()) h = 24f
        h = min(h, H - y0 - 8f)
        Ui.glow(sx + 6f, y0 + 18f, sw - 12f, h - 12f, 6.5f, 17f, 0x7C000000)
        Ui.blur(sx, y0, sw, h, 6.5f)
        Ui.box(sx, y0, sw, h, 6.5f, Colors.withAlpha(Tok.POP, ui.opacity.value + 0.14f), 0.5f, Tok.LINE2)
        Ui.block("results", sx, y0, sw, h)
        if (hits.isEmpty()) {
            Ui.textC(Type.small, "No module or setting matches \"${search.text}\"", sx + sw / 2f, y0 + 12f, Tok.T3)
            return
        }
        var y = y0 + 3f
        lastKind = -1
        for ((i, hit) in hits.withIndex()) {
            val kind = if (hit.setting == null) 0 else 1
            if (kind != lastKind) {
                Ui.text(Type.section, if (kind == 0) "MODULES" else "SETTINGS", sx + 9f, y + 7f, Tok.T4)
                y += RES_LABEL
                lastKind = kind
            }
            if (y + RES_ROW > y0 + h) break
            val over = Ui.hit("res:$i", sx + 3f, y, sw - 6f, RES_ROW - 1f, { if (it == Keys.MOUSE_LEFT) activate(hit) })
            if (over && cur != i) cur = i
            if (i == cur) Ui.box(sx + 3f, y, sw - 6f, RES_ROW - 1f, 4.5f, Tok.FILL2)
            val m = hit.module
            Ui.box(sx + 9f, y + 8f, 3.5f, 3.5f, 1.75f, if (hit.setting == null && m.enabled) Tok.a1 else 0x2EFFFFFF)
            val where = if (hit.setting == null) catName(m.subCategory) + " › " + subName(m.subCategory)
                else hit.setting.javaClass.simpleName.removeSuffix("Setting").lowercase(Locale.ROOT)
            val textW = sw - 17f - 14f - Type.chip.width(where) - 8f
            val name = if (hit.setting == null) GuiData.name(m) else GuiData.name(m) + " › " + GuiData.name(hit.setting)
            Ui.text(Type.semi, Ui.fit(Type.semi, name, textW), sx + 17f, y + 6.25f, Tok.T1)
            val sub = if (hit.setting == null) GuiData.description(m) else Inspector.valueText(hit.setting)
            Ui.text(Type.tiny, Ui.fit(Type.tiny, sub, textW), sx + 17f, y + 13.5f, Tok.T3)
            Ui.textR(Type.chip, where, sx + sw - 10f, y + 9.5f, Tok.T3)
            y += RES_ROW
        }
    }

    private fun activate(hit: SearchHit) {
        search.text = ""
        hits = emptyList()
        Ui.focused = null
        jumpTo(hit.module, hit.setting)
    }

    /** Show the module's tab and select it (it wiggles); a setting opens the module's settings at it. */
    fun jumpTo(m: Module, s: Setting<*>? = null) {
        go(viewOf(m.subCategory))
        if (filter != Filter.ALL) {
            filter = Filter.ALL
            enterView(0f)
        }
        val list = mods(view)
        val i = list.indexOf(m)
        if (i >= 0) gridScroll = max(0f, (i / 2) * (TILE_H + TILE_GAP) - 60f)
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
                popover != null -> {
                    // a popover's own field (a list's search) goes with it
                    popover = null
                    if (f !== search) Ui.focused = null
                }
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
