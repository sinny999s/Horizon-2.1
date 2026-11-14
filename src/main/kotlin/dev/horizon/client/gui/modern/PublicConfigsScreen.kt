package dev.horizon.client.gui.modern

import dev.horizon.client.config.PublicConfigData
import dev.horizon.client.config.PublicConfigs
import dev.horizon.client.config.PublicConfigs.Meta
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.gui.UrlTextures
import dev.horizon.client.lunar.HorizonShare
import dev.horizon.client.module.Config
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * Public configs in the new GUI's style ([PublicConfigs]). On the left, configs others published: search them (by
 * name, description or author), sort them (popular, most starred, recent), filter by a tag or show only yours. Pick one
 * to see who made it, what it's for and which modules it turns on, then Load it (it becomes a config of yours and is
 * switched to), star it, or report it (yours: take it down). Publish shares your current config: settings only, never
 * keybinds or anything personal ([dev.horizon.client.config.PublicConfigData]). Opened from the ClickGUI's profile menu
 * or `cfg browse`.
 */
object PublicConfigsScreen : Screen(Component.literal("horizon-public-configs")) {
    const val W = 560f
    const val H = 330f
    private const val BAR = 29f
    private const val STATUS = 15f
    private const val LIST = 228f
    private const val ROW = 30f
    private const val CHIPS = BAR + 6f
    private const val TOP = BAR + 27f

    private val mc get() = Minecraft.getInstance()
    private var mouseX = 0f
    private var mouseY = 0f
    private var ox = 0f
    private var oy = 0f
    private var k = 1f
    private var closing = false
    private var scroll = 0f
    private var scrollMax = 0f
    private val search = Ui.Field(max = 48) { searchAt = System.currentTimeMillis() + 350 }
    private var searchAt = 0L

    private var selected: Meta? = null
    private var publishing = false
    private val pubName = Ui.Field(max = 32)
    private val pubDesc = Ui.Field(max = 300)
    private val pubTags = LinkedHashSet<String>()

    /** What the last action did, shown in the status bar for a while ([noteUntil]). */
    private var note: String? = null
    private var noteBad = false
    private var noteUntil = 0L
    private var busy: String? = null

    /** A Report or Delete click waits for a second one until then (and on that config). */
    private var confirm: String? = null
    private var confirmUntil = 0L

    fun open() = mc.gui.setScreen(this)

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
        Ui.animFrom("pubcfg", 450, 0f)
        if (PublicConfigs.configs.isEmpty() && !PublicConfigs.loading) PublicConfigs.refresh()
    }

    override fun removed() {
        super.removed()
        mc.textInputManager().stopTextInput(this)
        Ui.focused = null
    }

    override fun onClose() {
        if (closing) return
        closing = true
        Ui.focused = null
        Ui.animFrom("pubcfg", 160, 1f, Easing.IN_QUAD)
    }

    /**
     * For ShotBot: picks the listed config named [what] (or the first one); or "publish" (the form), "tag:<tag>"
     * ("tag:all" for all), "mine", "search:<text>", "sort:<popular|top|recent>".
     */
    @JvmStatic
    fun show(what: String) {
        when {
            what == "publish" -> startPublish()
            what == "mine" -> filter(null, true)
            what.startsWith("tag:") -> filter(what.substring(4).takeIf { it != "all" }, false)
            what.startsWith("search:") -> {
                search.text = what.substring(7)
                searchAt = System.currentTimeMillis()
            }
            what.startsWith("sort:") -> {
                PublicConfigs.sort = PublicConfigs.Sort.entries.first { it.key == what.substring(5) }
                PublicConfigs.refresh()
            }
            else -> (PublicConfigs.configs.firstOrNull { it.name.equals(what, true) } ?: PublicConfigs.configs.firstOrNull())?.let { pick(it) }
        }
    }

    /** For ShotBot: presses the open config's Load, or Publish on the form. */
    @JvmStatic
    fun press(what: String) {
        when (what) {
            "load" -> selected?.let { load(it) }
            "publish" -> publish()
            "star" -> selected?.let { star(it) }
        }
    }

    private fun pick(m: Meta) {
        selected = m
        stopPublishing()
        confirm = null
    }

    /** Back from the publish form (its fields let go of the keyboard). */
    private fun stopPublishing() {
        publishing = false
        if (Ui.focused === pubName || Ui.focused === pubDesc) Ui.focused = null
    }

    private fun say(text: String, bad: Boolean = false) {
        note = text
        noteBad = bad
        noteUntil = System.currentTimeMillis() + 6000
    }

    /** One frame (from Overlay's screen layer). */
    fun draw() {
        val sw = width.toFloat()
        val sh = height.toFloat()
        k = min(1f, min((sw - 16f) / W, (sh - 16f) / H))
        ox = (sw - W * k) / 2f
        oy = (sh - H * k) / 2f
        val t = if (closing) Ui.anim("pubcfg", 0f, 160, Easing.IN_QUAD) else Ui.anim("pubcfg", 1f, 450)
        if (closing && t <= 0.02f) {
            closing = false
            super.onClose()
            return
        }
        if (searchAt > 0 && System.currentTimeMillis() >= searchAt) {
            searchAt = 0
            PublicConfigs.query = search.text
            PublicConfigs.refresh()
            scroll = 0f
        }
        // The open config as the list has it now (a star changes it).
        selected = selected?.let { s -> PublicConfigs.configs.firstOrNull { it.id == s.id } ?: s }
        Ui.mx = (mouseX - ox) / k
        Ui.my = (mouseY - oy) / k
        if (Ui.isDragging) Ui.drag()
        Ui.beginFrame()
        Canvas.alpha = t
        Ui.blur(0f, 0f, sw, sh, 0f)
        Ui.box(0f, 0f, sw, sh, 0f, 0x61000000)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(ox, oy)
        pose.scale(k, k)
        window()
        pose.popMatrix()
        Canvas.alpha = 1f
    }

    private fun window() {
        Ui.glow(15f, 40f, W - 30f, H - 30f, 11f, 40f, 0x8C000000.toInt())
        Ui.box(-0.5f, -0.5f, W + 1f, H + 1f, 11.5f, 0, 0.5f, 0x59000000)
        Ui.blur(0f, 0f, W, H, 11f)
        Ui.ambient(-90f, -50f)
        Ui.vbox(0f, 0f, W, H, 11f, 0xEB18151E.toInt(), 0xF20B0A0F.toInt(), 0.5f, 0x17FFFFFF)
        Ui.insetTop(0f, 0f, W, 11f, 0x12FFFFFF)
        Ui.fadeLine(W * 0.1f, 0f, W * 0.8f, 0.5f, Tok.alpha(Tok.light(Tok.a1, 0.3f), 0.55f))
        Ui.block("pubcfg", 0f, 0f, W, H)
        titleBar()
        chips()
        list()
        if (publishing) publishForm() else detail()
        statusBar()
    }

    /** The window's background, for fading the ends of the list ([Ui.fade]). */
    private val backdrop = Ui.Backdrop({ Ui.ambient(-90f, -50f) }, { y -> Ui.vboxAt(0f, H, 0xEB18151E.toInt(), 0xF20B0A0F.toInt(), y) })

    // ---- title bar: search, sort, publish ----

    private fun titleBar() {
        Ui.box(0f, BAR, W, 0.5f, 0f, Tok.LINE)
        Ui.glow(12.5f, 10.75f, 8f, 8f, 4f, 5f, Tok.alpha(Tok.a1, 0.3f))
        Ui.mark(9.5f, 7.75f, 14f)
        Type.brand.drawGradientMiddle("Configs", 28.5f, 14.75f, Tok.a1, Tok.a2)
        val vx = 28.5f + Type.brand.width("Configs") + 5f
        val vw = Type.ver.width("PUBLIC") + 7f
        Ui.box(vx, 10.25f, vw, 9f, 3f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.textC(Type.ver, "PUBLIC", vx + vw / 2f, 14.75f, Tok.T3)

        field(search, 150f, 5.75f, 180f, "search", "Search configs or authors")

        // Right to left: close, Publish, sort.
        val cx = W - 8.5f - 17f
        val o = Ui.hit("close", cx, 6.25f, 17f, 17f, { if (it == Keys.MOUSE_LEFT) onClose() })
        if (o) Ui.box(cx, 6.25f, 17f, 17f, 5f, Tok.FILL2)
        Ui.icon("x", cx + 4.5f, 10.75f, 8f, if (o) Tok.T1 else Tok.T2)
        var bx = cx - 6f
        val pw = Type.small.width("Publish") + 19f
        bx -= pw
        Ui.button("publish", bx, 5.75f, 18f, "Publish", "upload", accent = !publishing, active = publishing) {
            if (publishing) stopPublishing() else startPublish()
        }
        val sort = PublicConfigs.sort
        val sw = Type.small.width(sort.label) + 19f
        bx -= 4f + sw
        Ui.button("sort", bx, 5.75f, 18f, sort.label, "updown") {
            PublicConfigs.sort = PublicConfigs.Sort.entries[(sort.ordinal + 1) % PublicConfigs.Sort.entries.size]
            PublicConfigs.refresh()
            scroll = 0f
        }
    }

    private fun field(f: Ui.Field, x: Float, y: Float, w: Float, icon: String?, placeholder: String) {
        val focusedNow = Ui.focused === f
        val over = Ui.hit(Ui.fieldId(f), x, y, w, 18f, { if (it == Keys.MOUSE_LEFT) Ui.focused = f })
        if (focusedNow) Ui.box(x - 2f, y - 2f, w + 4f, 22f, 7.5f, 0, 2f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(x, y, w, 18f, 5.5f, if (focusedNow) 0x59000000 else 0x40000000, 0.5f,
            if (focusedNow) Tok.alpha(Tok.a1, 0.55f) else if (over) 0x26FFFFFF else Tok.LINE2)
        val tx = if (icon != null) {
            Ui.icon(icon, x + 6f, y + 5f, 8f, Tok.T3)
            x + 19f
        } else x + 7f
        Ui.fieldText(f, tx, y + 9f, w - (tx - x) - 7f, Type.label, placeholder)
    }

    // ---- tags ----

    private fun tagLabel(t: String) = when (t) {
        "pvp" -> "PvP"
        else -> t.replaceFirstChar { it.uppercase() }
    }

    /** A chip; returns its width. */
    private fun chip(id: String, x: Float, y: Float, label: String, on: Boolean, icon: String? = null, click: () -> Unit): Float {
        val w = Type.small.width(label) + 14f + (if (icon != null) 10f else 0f)
        val over = Ui.hit(id, x, y, w, 15f, { if (it == Keys.MOUSE_LEFT) click() })
        Ui.box(x, y, w, 15f, 7.5f, if (on) Tok.alpha(Tok.a1, 0.2f) else if (over) Tok.FILL2 else Tok.FILL, 0.5f,
            if (on) Tok.alpha(Tok.a1, 0.6f) else if (over) Tok.LINE2 else Tok.LINE)
        var tx = x + 7f
        if (icon != null) {
            Ui.icon(icon, tx, y + 4f, 7f, if (on || over) Tok.T1 else Tok.T2)
            tx += 10f
        }
        Ui.text(Type.small, label, tx, y + 7.5f, if (on) Tok.T1 else if (over) Tok.T1 else Tok.T2)
        return w
    }

    private fun chips() {
        var x = 8f
        val all = PublicConfigs.tag == null && !PublicConfigs.mine
        x += chip("tag:all", x, CHIPS, "All", all) { filter(null, false) } + 4f
        for (t in PublicConfigs.TAGS) {
            x += chip("tag:$t", x, CHIPS, tagLabel(t), PublicConfigs.tag == t && !PublicConfigs.mine) { filter(if (PublicConfigs.tag == t) null else t, false) } + 4f
        }
        val yw = Type.small.width("Yours") + 24f
        chip("tag:mine", W - 8f - yw, CHIPS, "Yours", PublicConfigs.mine, "player") { filter(null, !PublicConfigs.mine) }
        Ui.box(0f, TOP - 4f, W, 0.5f, 0f, Tok.LINE)
    }

    private fun filter(tag: String?, mine: Boolean) {
        PublicConfigs.tag = tag
        PublicConfigs.mine = mine
        PublicConfigs.refresh()
        scroll = 0f
    }

    // ---- the list ----

    private fun list() {
        Ui.box(LIST, TOP - 4f, 0.5f, H - TOP + 4f - STATUS, 0f, Tok.LINE)
        val gx = 6f
        val gy = TOP + 2f
        val gw = LIST - 12f
        val gh = H - STATUS - 6f - gy
        val items = PublicConfigs.configs
        val total = items.size * ROW + (if (PublicConfigs.more) ROW else 0f)
        scrollMax = max(0f, total - gh)
        scroll = scroll.coerceIn(0f, scrollMax)
        val sy = Ui.anim("pubcfgScroll", scroll, 300)
        Ui.hit("list", gx, gy, gw, gh, scroll = { a -> scroll = (scroll - a.toFloat() * ROW).coerceIn(0f, scrollMax); true }, pointer = false)
        // Near the end: the next page.
        if (PublicConfigs.more && !PublicConfigs.loading && sy >= scrollMax - ROW * 2) PublicConfigs.loadMore()
        Ui.pushClip(gx - 2f, gy, gw + 4f, gh)
        var y = gy - sy
        for (m in items) {
            if (y + ROW >= gy && y <= gy + gh) row(m, gx, y, gw, gy, gy + gh)
            y += ROW
        }
        if (PublicConfigs.more) Ui.textC(Type.small, "Loading more...", gx + gw / 2f, y + ROW / 2f, Tok.T4)
        Ui.popClip()
        Ui.fade(backdrop, gx - 2f, gy, gw + 4f, 8f, false, sy / 8f)
        Ui.fade(backdrop, gx - 2f, gy + gh, gw + 4f, 8f, true, (scrollMax - sy) / 8f)
        if (items.isEmpty()) {
            val cy = gy + gh / 2f
            val err = PublicConfigs.error
            val lines = when {
                PublicConfigs.loading -> listOf("Loading configs...")
                err != null -> listOf(err, "Click a tag or search again to retry")
                PublicConfigs.mine -> listOf("You haven't published any configs", "Publish shares your current one")
                PublicConfigs.query.isNotBlank() || PublicConfigs.tag != null -> listOf("No configs match", "Try another search or tag")
                else -> listOf("No public configs yet", "Be the first: Publish yours")
            }
            lines.forEachIndexed { i, s -> Ui.textC(Type.label, Ui.fit(Type.label, s, gw - 8f), LIST / 2f, cy - 5f + i * 10f, if (i == 0) Tok.T2 else Tok.T3) }
        }
        if (scrollMax > 0f) {
            val bh = max(20f, gh * gh / (gh + scrollMax))
            Ui.box(LIST - 4.5f, gy + (gh - bh) * (sy / scrollMax), 2f, bh, 1f, 0x26FFFFFF)
        }
    }

    private fun row(m: Meta, x: Float, y: Float, w: Float, top: Float, bottom: Float) {
        val y0 = max(y, top)
        val y1 = min(y + ROW - 2f, bottom)
        val over = y1 > y0 && Ui.hit("cfg:${m.id}", x, y0, w, y1 - y0, { if (it == Keys.MOUSE_LEFT) pick(m) })
        val on = !publishing && selected?.id == m.id
        if (on || over) Ui.box(x, y, w, ROW - 2f, 6f, if (on) Tok.FILL2 else Tok.FILL, 0.5f, if (on) Tok.alpha(Tok.a1, 0.6f) else Tok.LINE)
        head(m, x + 6f, y + 7f, 14f)
        val tx = x + 26f
        // Its stars on the right of the name.
        val stars = m.likes.toString()
        val sw = Type.small.width(stars) + 9f
        Ui.icon(if (m.liked) "favon" else "fav", x + w - 6f - sw, y + 6.5f, 6f, if (m.liked) Tok.light(Tok.a1, 0.2f) else Tok.T3)
        Ui.textR(Type.small, stars, x + w - 6f, y + 9.5f, Tok.T3)
        Ui.text(Type.semi, Ui.fit(Type.semi, m.name, w - 34f - sw), tx, y + 9.5f, Tok.T1)
        val by = "by ${m.author.name}" + (if (m.tags.isNotEmpty()) "  ·  " + m.tags.joinToString(", ") { tagLabel(it) } else "")
        Ui.text(Type.small, Ui.fit(Type.small, by, w - 32f), tx, y + 19.5f, Tok.T3)
    }

    /** The author's head (minotar by account id), a plain tile until it is in. */
    private fun head(m: Meta, x: Float, y: Float, size: Float) {
        val uuid = m.author.uuid.takeIf { Regex("[0-9a-f]{32}").matches(it) }
        val tex = uuid?.let { UrlTextures.find("https://minotar.net/helm/$it/32.png") }
        if (tex != null) {
            Canvas.texturedRect(tex, x, y, size, size, size / 4f, 0f, 0f, Canvas.alpha, -1, -1)
            Ui.box(x, y, size, size, size / 4f, 0, 0.5f, 0x1FFFFFFF)
        } else {
            Ui.box(x, y, size, size, size / 4f, Tok.FILL2, 0.5f, Tok.LINE)
            Ui.icon("config", x + (size - 7f) / 2f, y + (size - 7f) / 2f, 7f, Tok.T3)
        }
    }

    // ---- one config ----

    private fun detail() {
        val x0 = LIST + 0.5f
        val cw = W - x0
        val m = selected
        val mid = TOP + (H - TOP - STATUS) / 2f
        if (m == null) {
            Ui.icon("globe", x0 + cw / 2f - 10f, mid - 22f, 20f, Tok.T4)
            Ui.textC(Type.label, "Pick a config to see what it does", x0 + cw / 2f, mid + 6f, Tok.T3)
            Ui.textC(Type.small, "Loading one makes it a config of yours; your keybinds stay yours", x0 + cw / 2f, mid + 17f, Tok.T4)
            return
        }
        val px = x0 + 14f
        val pw = cw - 28f
        val hy = TOP + 4f
        head(m, px, hy + 2f, 22f)
        Ui.text(Type.name, Ui.fit(Type.name, m.name, pw - 150f), px + 29f, hy + 8f, Tok.T1)
        val when_ = if (m.updated > 0) "updated " + ago(m.updated * 1000) else ""
        val sub = listOf("by ${m.author.name}", when_, if (m.client.isNotEmpty()) "Horizon ${m.client}" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
        Ui.text(Type.small, Ui.fit(Type.small, sub, pw - 150f), px + 29f, hy + 18f, Tok.T3)

        // Buttons, right to left: Load, star, report (yours: delete).
        var bx = x0 + cw - 14f
        fun button(id: String, label: String?, icon: String, accent: Boolean = false, active: Boolean = false, click: () -> Unit) {
            val w = if (label == null) 18f else Type.small.width(label) + 19f
            bx -= w
            Ui.button(id, bx, hy + 4f, 18f, label, icon, accent = accent, active = active, click = click)
            bx -= 4f
        }
        button("load", if (busy == "load") "Loading..." else "Load", "load", accent = true) { load(m) }
        button("star", m.likes.toString(), if (m.liked) "favon" else "fav", active = m.liked) { star(m) }
        val sure = confirm == m.id && System.currentTimeMillis() < confirmUntil
        if (m.mine) button("delete", if (sure) "Take down?" else null, "trash", active = sure) { remove(m) }
        else button("report", if (sure) "Report?" else null, "flag", active = sure) { report(m) }

        var y = hy + 34f
        Ui.box(x0, y - 6f, cw, 0.5f, 0f, Tok.LINE)
        // What it's for.
        val desc = m.description.ifBlank { "No description." }
        for (l in Ui.wrap(Type.body, desc, pw, 4)) {
            Ui.text(Type.body, l, px, y + 4f, if (m.description.isBlank()) Tok.T3 else Tok.T2)
            y += 10f
        }
        y += 6f
        // Its numbers.
        var sx = px
        fun stat(icon: String, text: String) {
            Ui.icon(icon, sx, y + 1f, 7f, Tok.T3)
            Ui.text(Type.small, text, sx + 10f, y + 4.5f, Tok.T2)
            sx += 10f + Type.small.width(text) + 12f
        }
        stat("load", "${m.downloads} ${if (m.downloads == 1) "load" else "loads"}")
        stat("fav", "${m.likes} ${if (m.likes == 1) "star" else "stars"}")
        stat("sliders", "${m.settings} settings")
        if (m.mc.isNotEmpty()) stat("info", "Minecraft ${m.mc}")
        y += 16f
        if (m.tags.isNotEmpty()) {
            var tx = px
            for (t in m.tags) {
                val w = Type.small.width(tagLabel(t)) + 12f
                Ui.box(tx, y, w, 13f, 6.5f, Tok.FILL, 0.5f, Tok.LINE)
                Ui.text(Type.small, tagLabel(t), tx + 6f, y + 6.5f, Tok.T2)
                tx += w + 4f
            }
            y += 20f
        }
        // Which modules it turns on (not the personal ones: loading it never switches those).
        val names = m.enabled.filter { it !in PublicConfigData.PERSONAL }
            .map { id -> Modules.all.firstOrNull { it.id == id }?.name?.trim() ?: id }.sortedBy { it.lowercase() }
        Ui.text(Type.section, "TURNS ON  ${names.size}", px, y + 4f, Tok.T3)
        y += 11f
        val bottom = H - STATUS - 10f
        var mx = px
        var shown = 0
        for (n in names) {
            val w = Type.small.width(n) + 10f
            if (mx + w > px + pw) {
                mx = px
                y += 16f
            }
            if (y + 13f > bottom - 14f) break
            Ui.box(mx, y, w, 13f, 4f, Tok.FILL, 0.5f, Tok.LINE)
            Ui.text(Type.small, n, mx + 5f, y + 6.5f, Tok.T2)
            mx += w + 3f
            shown++
        }
        if (shown < names.size) Ui.text(Type.small, "and ${names.size - shown} more", px, min(y + 20f, bottom - 4f), Tok.T3)
        if (names.isEmpty()) Ui.text(Type.small, "No modules on: only settings", px, y + 6f, Tok.T3)
    }

    private fun load(m: Meta) {
        if (busy != null) return
        busy = "load"
        PublicConfigs.fetchData(m) { r ->
            busy = null
            r.onSuccess { (meta, entries) ->
                val changes = PublicConfigs.changes(entries)
                val id = PublicConfigs.adopt(meta, entries)
                Sounds.SWITCH_LEFT.play()
                say("Loaded \"${meta.name}\" as your config \"$id\" ($changes ${if (changes == 1) "setting" else "settings"} changed)")
            }.onFailure { say(PublicConfigs.message(it), true) }
        }
    }

    private fun star(m: Meta) {
        if (busy != null) return
        busy = "star"
        PublicConfigs.star(m, !m.liked) { r ->
            busy = null
            r.onSuccess { selected = it }.onFailure { say(PublicConfigs.message(it), true) }
        }
    }

    private fun report(m: Meta) {
        if (confirm != m.id || System.currentTimeMillis() > confirmUntil) {
            confirm = m.id
            confirmUntil = System.currentTimeMillis() + 4000
            return
        }
        confirm = null
        PublicConfigs.report(m, "reported in Horizon") { r ->
            r.onSuccess { say("Reported \"${m.name}\". Thanks") }.onFailure { say(PublicConfigs.message(it), true) }
        }
    }

    private fun remove(m: Meta) {
        if (confirm != m.id || System.currentTimeMillis() > confirmUntil) {
            confirm = m.id
            confirmUntil = System.currentTimeMillis() + 4000
            return
        }
        confirm = null
        PublicConfigs.delete(m) { r ->
            r.onSuccess {
                if (selected?.id == m.id) selected = null
                say("Took \"${m.name}\" down")
            }.onFailure { say(PublicConfigs.message(it), true) }
        }
    }

    // ---- publishing ----

    private fun startPublish() {
        publishing = true
        selected = null
        if (pubName.text.isBlank()) pubName.text = Config.currentName.replace('-', ' ').replace('_', ' ')
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.take(32)
        Ui.focused = pubName
    }

    private fun publishForm() {
        val x0 = LIST + 0.5f
        val cw = W - x0
        val px = x0 + 14f
        val pw = cw - 28f
        var y = TOP + 4f
        Ui.text(Type.name, "Publish your config", px, y + 8f, Tok.T1)
        Ui.text(Type.small, Ui.fit(Type.small, "Shares your current config, \"${Config.currentName}\", as it is now", pw), px, y + 18f, Tok.T3)
        y += 32f
        Ui.text(Type.flabel, "NAME", px, y + 3f, Tok.T3)
        field(pubName, px, y + 8f, pw, null, "3 to 32 characters")
        y += 32f
        Ui.text(Type.flabel, "WHAT IT'S FOR", px, y + 3f, Tok.T3)
        field(pubDesc, px, y + 8f, pw, null, "Optional: the game mode, how it plays, what to change")
        y += 32f
        Ui.text(Type.flabel, "TAGS  (UP TO ${PublicConfigs.MAX_TAGS})", px, y + 3f, Tok.T3)
        y += 8f
        var tx = px
        for (t in PublicConfigs.TAGS) {
            val w = Type.small.width(tagLabel(t)) + 14f
            if (tx + w > px + pw) {
                tx = px
                y += 19f
            }
            tx += chip("pub:$t", tx, y, tagLabel(t), t in pubTags) {
                if (!pubTags.remove(t) && pubTags.size < PublicConfigs.MAX_TAGS) pubTags += t
            } + 4f
        }
        y += 26f
        // What stays private.
        val lines = Ui.wrap(Type.small, "Only settings are shared. Your keybinds, Array List choices, text settings (chat messages, " +
            "commands, webhooks, passwords, coordinates, names) and the modules that send messages stay private. Publishing " +
            "again with the same name updates it.", pw - 27f, 4)
        val bh = lines.size * 9f + 10f
        Ui.box(px, y, pw, bh, 6f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.icon("eyeoff", px + 6f, y + 6f, 7f, Tok.T3)
        lines.forEachIndexed { i, l -> Ui.text(Type.small, l, px + 17f, y + 9.5f + i * 9f, Tok.T3) }
        y += bh + 10f
        val ready = pubName.text.trim().length >= 3
        val label = if (busy == "publish") "Publishing..." else "Publish"
        val w = Ui.button("pubGo", px, y, 18f, label, "upload", accent = ready) { publish() }
        Ui.button("pubCancel", px + w + 4f, y, 18f, "Cancel", "x") { stopPublishing() }
        val signed = HorizonShare.currentToken() != null
        if (!signed) Ui.text(Type.small, "Signs in with your Microsoft account", px + w + 4f + Type.small.width("Cancel") + 23f, y + 9f, Tok.T4)
    }

    private fun publish() {
        val name = pubName.text.trim()
        if (busy != null) return
        if (name.length < 3) {
            say("Give it a name of 3 to 32 characters", true)
            return
        }
        busy = "publish"
        PublicConfigs.publish(name, pubDesc.text, pubTags.toList()) { r ->
            busy = null
            r.onSuccess { m ->
                Sounds.ENABLE.play()
                say("Published \"${m.name}\"")
                pubDesc.text = ""
                pubTags.clear()
                pick(m)
            }.onFailure { say(PublicConfigs.message(it), true) }
        }
    }

    // ---- status bar ----

    private fun statusBar() {
        val y = H - STATUS
        Ui.box(0f, y, W, 0.5f, 0f, Tok.LINE)
        val n = note
        val left = when {
            n != null && System.currentTimeMillis() < noteUntil -> n
            PublicConfigs.loading -> "Loading..."
            else -> {
                val c = PublicConfigs.configs.size
                "$c${if (PublicConfigs.more) "+" else ""} ${if (c == 1) "config" else "configs"}"
            }
        }
        val bad = n != null && noteBad && System.currentTimeMillis() < noteUntil
        Ui.text(Type.status, Ui.fit(Type.status, left, W - 200f), 10f, y + STATUS / 2f, if (bad) 0xFFFF8A8A.toInt() else Tok.T3)
        val right = if (HorizonShare.currentToken() != null) "Signed in as ${mc.user.name}" else "Browsing needs no sign-in"
        Ui.textR(Type.status, right, W - 10f, y + STATUS / 2f, Tok.T3)
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            s < 86400 * 30 -> "${s / 86400}d ago"
            else -> SimpleDateFormat("d MMM yyyy").format(Date(ms))
        }
    }

    // ---- input ----

    private fun toWindow(x: Double, y: Double) {
        Ui.mx = ((x - ox) / k).toFloat()
        Ui.my = ((y - oy) / k).toFloat()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        toWindow(event.x(), event.y())
        val had = Ui.focused
        val id = Ui.press(event.button())
        if (had != null && id != Ui.fieldId(had)) Ui.focused = null
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        toWindow(event.x(), event.y())
        Ui.release()
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        toWindow(event.x(), event.y())
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        toWindow(x, y)
        Ui.scroll(scrollY)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val f = Ui.focused
        if (key == Keys.ESCAPE) {
            when {
                f != null -> Ui.focused = null
                publishing -> stopPublishing()
                else -> onClose()
            }
            return true
        }
        if (f != null) {
            when {
                key == Keys.ENTER && f === search -> searchAt = System.currentTimeMillis()
                key == Keys.ENTER && f === pubName -> Ui.focused = pubDesc
                key == Keys.ENTER && f === pubDesc -> publish()
                key == Keys.TAB && f === pubName -> Ui.focused = pubDesc
                key == Keys.TAB -> Ui.focused = null
                else -> f.key(key)
            }
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val s = event.codepointAsString()
        // Typing with nothing focused searches.
        if (s.isNotBlank() && Ui.focused == null && !publishing) Ui.focused = search
        Ui.focused?.let { f -> s.forEach { f.type(it) } }
        return true
    }
}
