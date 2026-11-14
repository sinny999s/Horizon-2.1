package dev.horizon.client.gui.modern

import com.mojang.blaze3d.Blaze3D
import dev.horizon.client.config.ChatMacro
import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.config.Friends
import dev.horizon.client.config.ItemMacro
import dev.horizon.client.config.Macro
import dev.horizon.client.config.Macros
import dev.horizon.client.config.Proxies
import dev.horizon.client.config.Proxy
import dev.horizon.client.config.SettingMacro
import dev.horizon.client.config.WayPoint
import dev.horizon.client.config.Waypoints
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.Sounds
import dev.horizon.client.gui.UrlTextures
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.GroupSetting
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private val mc get() = Minecraft.getInstance()
private val W = ModernWindows

/**
 * The mockup's list + editor window: rows on the left (each with its own controls; a click or a right click
 * opens one), the open entry's fields on the right, Delete at the bottom.
 */
internal abstract class ListPane<T : Any>(id: String, title: String, icon: String, x0: Float, y0: Float, w0: Float, h0: Float,
                                          private val listW: Float, private val emptyTitle: String, private val emptyText: String,
                                          private val editorIcon: String) : Pane(id, title, icon, x0, y0, w0, h0) {
    var sel: T? = null
    private var selAt = 0L
    private var scroll = 0f
    private var reveal = false
    private val fields = HashMap<String, Ui.Field>()

    abstract fun items(): List<T>

    override val count get() = items().size

    /** The row's contents; its box is drawn already. */
    abstract fun row(item: T, x: Float, y: Float, w: Float, over: Boolean, selected: Boolean)

    abstract fun editor(item: T, x: Float, y: Float, w: Float, h: Float)

    fun select(item: T?, quiet: Boolean = false) {
        if (item === sel) return
        sel = item
        selAt = System.currentTimeMillis()
        reveal = item != null
        for (f in fields.values) {
            if (Ui.focused === f) Ui.focused = null
            W.styles.remove(f)
        }
        fields.clear()
        if (!quiet) Sounds.SWITCH_RIGHT.play()
    }

    /** The open entry's field [key], made with its value when the entry opens; typing runs [set]. */
    protected fun field(key: String, max: Int = 64, style: ModernWindows.FieldStyle? = null, init: () -> String, set: (String) -> Unit): Ui.Field =
        fields.getOrPut(key) { Ui.Field(init(), max) { set(it) }.also { f -> if (style != null) W.styles[f] = style } }

    override fun opened() {
        if (sel == null || items().none { it === sel }) select(items().firstOrNull(), true)
    }

    override fun body(x: Float, y: Float, w: Float, h: Float) {
        val list = items().toList()
        if (sel != null && list.none { it === sel }) select(list.firstOrNull(), true)
        Ui.box(x + listW - 0.5f, y, 0.5f, h, 0f, Tok.LINE)
        val contentH = 8f + list.size * 24.5f - (if (list.isEmpty()) 0f else 1.5f)
        val maxScroll = max(0f, contentH - h)
        // a newly opened entry scrolls into view
        if (reveal) {
            reveal = false
            val i = list.indexOfFirst { it === sel }
            if (i >= 0) {
                val top = 4f + i * 24.5f
                if (top < scroll) scroll = top - 4f
                if (top + 23f > scroll + h) scroll = top + 23f + 4f - h
            }
        }
        scroll = scroll.coerceIn(0f, maxScroll)
        val sy = Ui.anim("wl:$id", scroll, 300)
        Ui.hit("wlist:$id", x, y, listW, h, scroll = { a -> scroll = (scroll - a.toFloat() * 24.5f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, y, listW - 0.5f, h)
        var ry = y + 4f - sy
        for (item in list) {
            val rx = x + 4f
            val rw = listW - 8.5f
            if (ry + 23f >= y && ry <= y + h) {
                val selected = item === sel
                val over = Ui.hit("wrow:$id:" + System.identityHashCode(item), rx, ry, rw, 23f, { b ->
                    if (b == Keys.MOUSE_LEFT || b == Keys.MOUSE_RIGHT) select(item)
                })
                when {
                    selected -> Ui.box(rx, ry, rw, 23f, 5.5f, Tok.alpha(Tok.light(Tok.a1, 0.1f), 0.142f), 0.5f, Tok.alpha(Tok.a1, 0.34f))
                    over -> Ui.box(rx, ry, rw, 23f, 5.5f, 0x0AFFFFFF)
                }
                row(item, rx, ry, rw, over, selected)
            }
            ry += 24.5f
        }
        if (list.isEmpty()) W.empty(icon, emptyTitle, emptyText, x + listW / 2f, y + h / 2f, listW)
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + listW - 4f, y + 1.5f, h - 3f, sy, maxScroll)

        // the editor (padding 6, 7 below)
        val ex = x + listW + 6f
        val ew = w - listW - 12f
        val s = sel
        if (s == null) {
            W.empty(editorIcon, "Nothing open", "Pick an entry on the left, or add one with +.", ex + ew / 2f, y + h / 2f, ew)
            return
        }
        val e = Easing.OUT_EXPO.apply(((System.currentTimeMillis() - selAt) / 450.0).coerceIn(0.0, 1.0)).toFloat()
        val pa = Canvas.alpha
        Canvas.alpha = pa * e
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(0f, (1f - e) * 4f)
        editor(s, ex, y + 6f, ew, h - 13f)
        pose.popMatrix()
        Canvas.alpha = pa
    }

    /** A row's two lines, [w] wide from [x]: bold [title] ([dim]: faded), small [sub]. */
    protected fun tx(title: String, sub: String, x: Float, y: Float, w: Float, dim: Boolean = false) {
        Ui.text(Type.semi, Ui.fit(Type.semi, title, w), x, y + 7.5f, if (dim) Tok.T3 else Tok.T1)
        Ui.text(Type.tiny, Ui.fit(Type.tiny, sub, w), x, y + 16.1f, Tok.T3)
    }

    /** A line of small text with bold parts (the mockup's .fnote). */
    protected fun note(parts: List<Pair<String, Boolean>>, x: Float, cy: Float) {
        var tx = x + 2f
        for ((t, bold) in parts) {
            val f = if (bold) Type.statusB else Type.status
            Ui.text(f, t, tx, cy, if (bold) Tok.T2 else Tok.T3)
            tx += f.width(t)
        }
    }
}

// ---- Configs ----

/**
 * A Modules | HUD switch and a tile per config. Double click a tile to load it; right click
 * a tile for Load / Open in Explorer / Delete, or empty space for Save. The loaded config's tile shimmers in the theme.
 * The loaded config can't be deleted.
 */
internal object ConfigsPane : Pane("configs", "Configs", "config", 50f, 47f, 220f, 180f) {
    private var tab = ConfigType.MODULES
    private var scroll = 0f
    private var lastClick = 0L
    private var lastTile: String? = null
    private var popName: String? = null
    private var popAt = 0L

    override val count get() = Configs.of(tab).list.size
    override val hints = listOf("2×" to "click loads", "RMB" to "more", "" to "drop a config file to import")

    override fun tools(right: Float, y: Float) {
        val bw = W.buttonWidth("Save as", "save")
        W.wbtn("cfg:save", right - bw, y, "Save as", "save", primary = true) { saveAs() }
    }

    /** The new HUD editor's Save as: this window on HUD, its dialog open. */
    internal fun saveHud() {
        tab = ConfigType.WIDGETS
        saveAs()
    }

    private fun saveAs() {
        val modules = tab == ConfigType.MODULES
        W.openDialog(this, Dialog(if (modules) "Save module config" else "Save HUD config",
            "Saves the current ${if (modules) "modules and settings" else "HUD layout"} under a new name.", 10, "Save") { name ->
            Configs.create(name, tab)
            Sounds.SWITCH_RIGHT.play()
            W.notify("Configs", "saved $name", NotificationType.SUCCESS)
            pop(name)
        })
    }

    /** Also the new HUD editor's config menu. */
    internal fun load(name: String, type: ConfigType = tab) {
        if (Configs.of(type).current.id == name) {
            W.notify("Configs", "$name is already loaded")
            return
        }
        Configs.load(name, type)
        Sounds.SWITCH_LEFT.play()
        W.notify("Configs", "loaded $name", NotificationType.SUCCESS)
        pop(name)
    }

    private fun delete(name: String) {
        if (Configs.of(tab).current.id == name) {
            W.notify("Configs", "the loaded config can't be deleted", NotificationType.WARNING)
            return
        }
        Configs.delete(name, tab)
        Sounds.DISABLE.play()
        W.notify("Configs", "deleted $name")
    }

    private fun pop(name: String) {
        popName = name
        popAt = System.currentTimeMillis()
    }

    override fun body(x: Float, y: Float, w: Float, h: Float) {
        Ui.seg("cfg:tab", x + 6f, y + 6f, w - 12f, 18f, listOf("Modules", "HUD"), tab.ordinal) {
            tab = ConfigType.entries[it]
            scroll = 0f
            Sounds.SWITCH_RIGHT.play()
        }
        val gy = y + 24f
        val gh = h - 24f
        val names = Configs.of(tab).list.map { it.id }
        val cols = max(1, floor((w - 12f + 4f) / 66f).toInt())
        val tw = (w - 12f - 4f * (cols - 1)) / cols
        val rows = ceil((names.size + 1) / cols.toFloat()).toInt()
        val maxScroll = max(0f, 6f + rows * 52f - 4f + 6f - gh)
        scroll = scroll.coerceIn(0f, maxScroll)
        val sy = Ui.anim("cgrid", scroll, 300)
        Ui.hit("cgrid", x, gy, w, gh, { b ->
            if (b == Keys.MOUSE_RIGHT) W.menu = Menu(Ui.mx, Ui.my, listOf(Menu.Item("save", "Save current as...") { saveAs() }))
        }, scroll = { a -> scroll = (scroll - a.toFloat() * 26f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, gy, w, gh)
        for ((i, name) in names.withIndex()) {
            val tx = x + 6f + (i % cols) * (tw + 4f)
            val ty = gy + 6f + (i / cols) * 52f - sy
            if (ty + 48f >= gy && ty <= gy + gh) tile(name, tx, ty, tw)
        }
        val ai = names.size
        addTile(x + 6f + (ai % cols) * (tw + 4f), gy + 6f + (ai / cols) * 52f - sy, tw)
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + w - 4f, gy + 1.5f, gh - 3f, sy, maxScroll)
    }

    private fun tile(name: String, x: Float, y0: Float, w: Float) {
        val cur = Configs.of(tab).current.id == name
        val over = Ui.hit("tile:$name", x, y0, w, 48f, { b ->
            when (b) {
                Keys.MOUSE_LEFT -> {
                    val now = System.currentTimeMillis()
                    if (lastTile == name && now - lastClick < 400) {
                        lastTile = null
                        load(name)
                    } else {
                        lastTile = name
                        lastClick = now
                    }
                }
                Keys.MOUSE_RIGHT -> W.menu = Menu(Ui.mx, Ui.my, listOf(
                    Menu.Item("load", "Load") { load(name) },
                    Menu.Item("folder", "Open in Explorer") { Blaze3D.openPath(Configs.of(tab).dir) },
                    Menu.Item("trash", "Delete", danger = true) { delete(name) },
                ))
            }
        })
        val lift = Ui.anim("tlift:$name", if (over) 1f else 0f, 350)
        val y = y0 - lift
        // the "loaded" pop: up to 1.05 and back
        val pt = (System.currentTimeMillis() - popAt) / 600f
        val pose = Canvas.pose
        pose.pushMatrix()
        if (popName == name && pt in 0f..1f) {
            val s = 1f + 0.05f * sin(pt * Math.PI).toFloat()
            pose.translate(x + w / 2f, y + 24f)
            pose.scale(s, s)
            pose.translate(-(x + w / 2f), -(y + 24f))
        }
        if (cur) {
            Ui.pushClip(x - 12f, y + 48f, w + 24f, 14f)
            Ui.glow(x + 8f, y + 38f, w - 16f, 10f, 6.5f, 8f, Tok.alpha(Tok.a1, 0.5f))
            Ui.popClip()
            Ui.box(x, y, w, 48f, 6.5f, 0x08FFFFFF)
            Ui.dbox(x, y, w, 48f, 6.5f, Tok.alpha(Tok.a1, 0.16f), Tok.alpha(Tok.a2, 0.04f), 0.5f, Tok.alpha(Tok.a1, 0.45f))
        } else {
            Ui.box(x, y, w, 48f, 6.5f, if (over) 0x0EFFFFFF else 0x09FFFFFF, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        }
        // the icon box
        val ix = x + 6.5f
        val iy = y + 6.5f
        if (cur) {
            Ui.glow(ix, iy + 2f, 16f, 16f, 5f, 4f, Tok.alpha(Tok.a1, 0.4f))
            Ui.dbox(ix, iy, 16f, 16f, 5f, Tok.a1, Tok.a2)
        } else {
            Ui.box(ix, iy, 16f, 16f, 5f, Tok.FILL2)
        }
        Ui.icon(if (tab == ConfigType.MODULES) "grid" else "layout", ix + 4f, iy + 4f, 8f, if (cur) Tok.WHITE else Tok.T2)
        if (cur) {
            val bw = Type.chip.width("Loaded") + 7f
            Ui.box(x + w - 5.5f - bw, y + 6.5f, bw, 10f, 3f, Tok.alpha(Tok.a1, 0.15f))
            Ui.textC(Type.chip, "Loaded", x + w - 5.5f - bw / 2f, y + 11.5f, Tok.light(Tok.a1, 0.25f))
        }
        Ui.text(Type.semi, Ui.fit(Type.semi, name, w - 13f), x + 6.5f, y + 30.1f, Tok.T1)
        val small = if (!cur) "double click to load" else if (tab == ConfigType.MODULES) {
            val on = Modules.all.count { it.enabled }
            "$on module${if (on == 1) "" else "s"} on"
        } else "current layout"
        Ui.text(Type.tiny, Ui.fit(Type.tiny, small, w - 13f), x + 6.5f, y + 38.2f, Tok.T3)
        // the shimmer over the loaded one (a light band crossing it every 3.2 s)
        if (cur) {
            val t = (System.currentTimeMillis() % 3200L) / 3200f
            val c = x - 0.78f * w + 4.16f * w * t
            val bw = 0.39f * w
            Ui.pushClip(x, y, w, 48f)
            Ui.hbox(c - bw, y, bw, 48f, 0f, 0, 0x17FFFFFF)
            Ui.hbox(c, y, bw, 48f, 0f, 0x17FFFFFF, 0)
            Ui.popClip()
        }
        pose.popMatrix()
    }

    private fun addTile(x: Float, y: Float, w: Float) {
        val over = Ui.hit("tile:+", x, y, w, 48f, { b -> if (b == Keys.MOUSE_LEFT) saveAs() })
        if (over) Ui.box(x, y, w, 48f, 6.5f, Tok.alpha(Tok.a1, 0.06f))
        dashed(x, y, w, 48f, 6.5f, if (over) Tok.alpha(Tok.a1, 0.5f) else 0x1FFFFFFF)
        val c = if (over) Tok.T1 else Tok.T3
        val total = 9f + 4f + 7.2f
        val top = y + (48f - total) / 2f
        Ui.icon("plus", x + w / 2f - 4.5f, top, 9f, c)
        Ui.textC(Type.semiS, "Save current as...", x + w / 2f, top + 13f + 3.6f, c)
    }

    /** CSS's 1px dashed border: dashes along the straight edges. */
    private fun dashed(x: Float, y: Float, w: Float, h: Float, r: Float, c: Int) {
        var dx = x + r
        while (dx < x + w - r) {
            val l = min(1.5f, x + w - r - dx)
            Ui.box(dx, y, l, 0.5f, 0f, c)
            Ui.box(dx, y + h - 0.5f, l, 0.5f, 0f, c)
            dx += 3f
        }
        var dy = y + r
        while (dy < y + h - r) {
            val l = min(1.5f, y + h - r - dy)
            Ui.box(x, dy, 0.5f, l, 0f, c)
            Ui.box(x + w - 0.5f, dy, 0.5f, l, 0f, c)
            dy += 3f
        }
        // the corners, drawn whole (a dashed quarter circle is too small to tell)
        for ((cx, cy) in listOf(x to y, x + w - 2f * r to y, x to y + h - 2f * r, x + w - 2f * r to y + h - 2f * r)) {
            Ui.pushClip(if (cx == x) x else x + w - r, if (cy == y) y else y + h - r, r, r)
            Ui.box(cx, cy, 2f * r, 2f * r, r, 0, 0.5f, c)
            Ui.popClip()
        }
    }

    private fun min(a: Float, b: Float) = kotlin.math.min(a, b)
}

// ---- Waypoints ----

/**
 * The eye shows or hides a waypoint in the world, a click opens it
 * in the editor. "+" adds one where you stand (at 0, 0, 0 off a server, on purpose).
 */
internal object WaypointsPane : ListPane<WayPoint>("waypoints", "Waypoints", "pin", 280f, 47f, 235f, 180f, 103f,
    "No waypoints", "Add one with +; it goes where you stand.", "pin") {
    private val dims = listOf("Overworld", "Nether", "End")

    override fun items() = Waypoints.list
    override val hints = listOf("" to "eye shows or hides it in the world", "RMB" to "edit")

    override fun tools(right: Float, y: Float) {
        W.wbtn("wp:add", right - 15f, y, null, "plus", primary = true) { add() }
    }

    private fun add() {
        val p = mc.player
        val level = mc.level
        val sd = mc.currentServer
        val text = if (p != null && level != null && sd != null) {
            "Placed where you stand: ${p.x.toInt()}, ${p.y.toInt()}, ${p.z.toInt()} in the " +
                "${dims[Waypoints.dimension(level.dimension().identifier().path)]} on ${sd.ip}."
        } else "Placed at 0, 0, 0: you're not on a server."
        W.openDialog(this, Dialog("New waypoint", text, 16) { id ->
            val wp = if (p != null && level != null && sd != null) {
                WayPoint(id, p.x.toInt(), p.y.toInt(), p.z.toInt(), sd.ip, Waypoints.dimension(level.dimension().identifier().path), true)
            } else WayPoint(id, 0, 0, 0)
            Waypoints.list += wp
            select(wp)
            W.notify("Waypoints", "added $id", NotificationType.SUCCESS)
        })
    }

    override fun row(item: WayPoint, x: Float, y: Float, w: Float, over: Boolean, selected: Boolean) {
        val ex = x + w - 4f - 14f
        tx(item.id, "${item.server} · ${dims[item.dimension.coerceIn(0, 2)]}", x + 5.5f, y, ex - 4.5f - x - 5.5f, dim = !item.visible)
        val eo = Ui.hit("eye:" + System.identityHashCode(item), ex, y + 4.5f, 14f, 14f, { b ->
            if (b == Keys.MOUSE_LEFT) {
                item.visible = !item.visible
                if (item.visible) Sounds.ENABLE.play() else Sounds.DISABLE.play()
            }
        })
        if (eo) Ui.box(ex, y + 4.5f, 14f, 14f, 4f, Tok.FILL2)
        Ui.icon(if (item.visible) "eye" else "eyeoff", ex + 3.25f, y + 7.75f, 7.5f,
            if (item.visible) Tok.light(Tok.a1, 0.15f) else if (eo) Tok.T2 else Tok.T4)
    }

    override fun editor(item: WayPoint, x: Float, y: Float, w: Float, h: Float) {
        var yy = y
        val half = (w - 4f) / 2f
        W.field(field("name", 16, init = { item.id }) { item.id = it }, x, yy, half, "Name", null, "")
        W.field(field("server", init = { item.server }) { item.server = it }, x + half + 4f, yy, half, "Server", null, "")
        yy += 21f
        val third = (w - 8f) / 3f
        val num = ModernWindows.FieldStyle(numeric = true)
        W.field(field("x", 12, num, { item.x.toString() }) { item.x = it.toIntOrNull() ?: 0 }, x, yy, third, "X", null, "")
        W.field(field("y", 12, num, { item.y.toString() }) { item.y = it.toIntOrNull() ?: 0 }, x + third + 4f, yy, third, "Y", null, "")
        W.field(field("z", 12, num, { item.z.toString() }) { item.z = it.toIntOrNull() ?: 0 }, x + 2f * (third + 4f), yy, third, "Z", null, "")
        yy += 21f - 1f
        if (item.dimension == 2) {
            note(listOf("The End has no Overworld or Nether position." to false), x, yy + 3.5f)
        } else {
            val f = if (item.dimension == 1) 8.0 else 1.0 / 8.0
            note(listOf("In the ${if (item.dimension == 1) "Overworld" else "Nether"}: " to false, "${(item.x * f).roundToInt()}" to true,
                ", " to false, "${(item.z * f).roundToInt()}" to true), x, yy + 3.5f)
        }
        yy += 7f + 4f
        yy += W.flabel("Dimension", x, yy) + 4f
        Ui.seg("wp:dim", x, yy, w, 18f, dims, item.dimension.coerceIn(0, 2)) {
            item.dimension = it
            Sounds.SWITCH_RIGHT.play()
        }
        W.deleteButton("wp:" + System.identityHashCode(item), x, y + h - 18f, w, "Delete waypoint") {
            Waypoints.list.remove(item)
            Sounds.DISABLE.play()
            W.notify("Waypoints", "deleted ${item.id}")
            select(Waypoints.list.firstOrNull(), true)
        }
    }
}

// ---- Proxies ----

/**
 * The radio picks the proxy for the next connections (again for none), a click opens it in
 * the editor; each shows its last ping. Test pings the open one, and the signal button pings them all.
 */
internal object ProxiesPane : ListPane<Proxy>("proxies", "Proxies", "globe", 525f, 47f, 225f, 180f, 103f,
    "No proxies", "Add one with +, then fill in its address.", "globe") {
    override fun items() = Proxies.list
    override val hints = listOf("" to "the selected proxy is used for the next connections", "RMB" to "edit")

    override fun tools(right: Float, y: Float) {
        W.wbtn("px:add", right - 15f, y, null, "plus", primary = true) {
            W.openDialog(this, Dialog("New proxy", "Give it a name, then its address in the editor.", 16) { id ->
                val p = Proxy(id, "", 0, "", "", false)
                Proxies.list += p
                select(p)
            })
        }
        W.wbtn("px:ping", right - 33f, y, null, "signal") { Proxies.pingAll() }
    }

    override fun row(item: Proxy, x: Float, y: Float, w: Float, over: Boolean, selected: Boolean) {
        // the radio
        val on = item == Proxies.selected
        val rid = "radio:" + System.identityHashCode(item)
        val ro = Ui.hit(rid, x + 4f, y + 5f, 12f, 13f, { b ->
            if (b != Keys.MOUSE_LEFT) return@hit
            Proxies.selected = if (on) null else item
            Sounds.SWITCH_RIGHT.play()
            W.notify("Proxies", if (Proxies.selected != null) "connecting through ${item.id}" else "connecting directly")
        })
        val t = Ui.anim("$rid/on", if (on) 1f else 0f, 350, Easing.OUT_BACK)
        Ui.box(x + 5.5f, y + 7f, 9f, 9f, 4.5f, 0, 0.75f, if (on) Tok.a1 else if (ro) 0x80FFFFFF.toInt() else 0x40FFFFFF)
        if (t > 0.01f) {
            val d = 6f * t
            Ui.glow(x + 10f - d / 2f, y + 11.5f - d / 2f, d, d, d / 2f, 3f, Tok.alpha(Tok.a1, 0.5f * t))
            Ui.dbox(x + 10f - d / 2f, y + 11.5f - d / 2f, d, d, d / 2f, Tok.a1, Tok.a2)
        }
        // the ping
        val (label, dot) = when {
            item.ping == -2 -> "..." to -2
            item.ping < 0 -> "offline" to -1
            else -> "${item.ping} ms" to when {
                item.ping < 80 -> 0xFF34D77B.toInt()
                item.ping < 150 -> 0xFFF5C542.toInt()
                else -> 0xFFFF6B6B.toInt()
            }
        }
        val pw = 3.5f + 3f + 2.5f + Type.chip.width(label) + 3.5f
        val px = x + w - 4f - pw
        Ui.box(px, y + 6.5f, pw, 10f, 3f, Tok.FILL)
        val dc = when (dot) {
            -2 -> Colors.withAlpha(Tok.T3, Colors.alphaF(Tok.T3) * (0.2f + 0.8f * (0.5f + 0.5f * sin(System.currentTimeMillis() / 800.0 * Math.PI * 2).toFloat())))
            -1 -> Tok.T4
            else -> dot
        }
        if (dot != -1 && dot != -2) Ui.glow(px + 3.5f, y + 10f, 3f, 3f, 1.5f, 3f, Colors.withAlpha(dot, 0.7f))
        Ui.box(px + 3.5f, y + 10f, 3f, 3f, 1.5f, dc)
        Ui.text(Type.chip, label, px + 9f, y + 11.5f, Tok.T2)
        tx(item.id, item.ip.ifEmpty { "no address yet" }, x + 19f, y, px - 4.5f - x - 19f)
    }

    override fun editor(item: Proxy, x: Float, y: Float, w: Float, h: Float) {
        var yy = y
        W.field(field("name", 16, init = { item.id }) { item.id = it }, x, yy, w, "Name", 33f, "")
        yy += 21f
        // the mockup's 1.8 : 1, but the port keeps room for four digits
        val ipW = kotlin.math.min((w - 4f) * 1.8f / 2.8f, w - 4f - (Type.small.width("Port") + 11.5f + Type.label.width("9999") + 6f))
        W.field(field("ip", init = { item.ip }) { item.ip = it }, x, yy, ipW, "IP", null, "203.0.113.24")
        W.field(field("port", 6, ModernWindows.FieldStyle(numeric = true), { if (item.port == 0) "" else item.port.toString() }) {
            item.port = abs(it.toIntOrNull() ?: 25565)
        }, x + ipW + 4f, yy, w - ipW - 4f, "Port", null, "1080")
        yy += 21f
        val mask = ModernWindows.FieldStyle(mask = true)
        W.field(field("login", 64, mask, { item.user }) { item.user = it }, x, yy, w, "Login", 33f, "none")
        yy += 21f
        W.field(field("pass", 64, mask, { item.password }) { item.password = it }, x, yy, w, "Pass", 33f, "none")
        yy += 21f
        Ui.seg("px:socks", x, yy, w, 18f, listOf("SOCKS5", "SOCKS4"), if (item.socks4) 1 else 0) {
            item.socks4 = it == 1
            Sounds.SWITCH_RIGHT.play()
        }
        val half = (w - 4f) / 2f
        W.wbtn("px:test", x, y + h - 18f, "Test", "signal", h = 18f, w = half) { Proxies.ping(item) }
        W.deleteButton("px:" + System.identityHashCode(item), x + half + 4f, y + h - 18f, half, "Delete") {
            Proxies.list.remove(item)
            Sounds.DISABLE.play()
            W.notify("Proxies", "deleted ${item.id}")
            select(Proxies.list.firstOrNull(), true)
        }
    }
}

// ---- Macros ----

/**
 * The switch turns a macro on, its key waits for a bind (the next key or button; Esc,
 * Backspace or Delete clear it), a click opens it in the editor. "+" makes a chat, item or setting macro.
 */
internal object MacrosPane : ListPane<Macro>("macros", "Macros", "macro", 50f, 236f, 300f, 180f, 136f,
    "No macros", "A key that sends a chat line, uses an item or sets a setting.", "macro") {
    private val itemNames: List<String> by lazy {
        BuiltInRegistries.BLOCK.map { it.descriptionId.replace("block.minecraft.", "") } +
            BuiltInRegistries.ITEM.map { it.descriptionId.replace("item.minecraft.", "") }
    }
    private val types = listOf("Chat", "Item", "Setting")
    private var newType = 0

    override fun items() = Macros.list
    override val hints = listOf("" to "switch turns it on", "" to "click the key to bind", "RMB" to "edit")

    override fun tools(right: Float, y: Float) {
        W.wbtn("mc:add", right - 15f, y, null, "plus", primary = true) {
            newType = 0
            W.openDialog(this, Dialog("New macro", null, 10, extra = { x, yy, w ->
                Ui.seg("mc:newtype", x, yy, w, 18f, types, newType) {
                    newType = it
                    Sounds.SWITCH_RIGHT.play()
                }
            }) { id ->
                val m = when (newType) {
                    0 -> ChatMacro(id, Bind(), true, "")
                    1 -> ItemMacro(id, Bind(), true, "", false)
                    else -> SettingMacro(id, Bind(), true, "", "", "0", "0", false)
                }
                Macros.list += m
                select(m)
                W.notify("Macros", "created $id", NotificationType.SUCCESS)
            })
        }
    }

    private fun summary(m: Macro) = when (m) {
        is ChatMacro -> m.message.ifEmpty { "no text yet" }
        is ItemMacro -> "${m.item.ifEmpty { "no item yet" }} · ${if (m.legit) "Legit" else "Rage"}"
        is SettingMacro -> "${m.module.ifEmpty { "?" }}.${m.setting.ifEmpty { "?" }} ${m.pressValue} → ${m.releaseValue}"
    }

    override fun row(item: Macro, x: Float, y: Float, w: Float, over: Boolean, selected: Boolean) {
        val hot = item.enabled || selected
        Ui.box(x + 5.5f, y + 4f, 15f, 15f, 4.5f, if (hot) Tok.alpha(Tok.a1, 0.16f) else Tok.FILL2)
        Ui.icon(when (item) {
            is ChatMacro -> "chat"
            is ItemMacro -> "box"
            else -> "sliders"
        }, x + 9.25f, y + 7.75f, 7.5f, if (hot) Tok.light(Tok.a1, 0.15f) else Tok.T2)
        val sx = x + w - 4f - 17f
        Ui.switch("mc:" + System.identityHashCode(item), sx, y + 6.5f, item.enabled) {
            item.enabled = !item.enabled
            if (item.enabled) Sounds.ENABLE.play() else Sounds.DISABLE.play()
        }
        val listening = W.listening === item
        val none = item.bind.key == -1
        val label = if (listening) "..." else if (none) "None" else Keys.name(item.bind)
        val kw = Type.chip.width(label) + 6f
        val kx = sx - 4.5f - kw
        Ui.hit("mkey:" + System.identityHashCode(item), kx, y + 7f, kw, 9f, { b -> if (b == Keys.MOUSE_LEFT) W.listen(item) })
        if (listening) {
            val br = 0.5f + 0.3f * sin(System.currentTimeMillis() / 1200.0 * Math.PI * 2).toFloat()
            Ui.box(kx, y + 7f, kw, 9f, 2.5f, 0x0FFFFFFF, 0.5f, Tok.alpha(Tok.a1, br))
            Ui.textC(Type.chip, label, kx + kw / 2f, y + 11.25f, Tok.WHITE)
        } else {
            Ui.keycap(kx, y + 7f, label, none = none)
        }
        tx(item.id, summary(item), x + 25f, y, kx - 4.5f - x - 25f, dim = !item.enabled)
    }

    /** The macro's key as a field: click it, then press the key. */
    private fun keyRow(item: Macro, x: Float, y: Float, w: Float) {
        val listening = W.listening === item
        val over = Ui.hit("mkeyrow:" + System.identityHashCode(item), x, y, w, 17f, { b -> if (b == Keys.MOUSE_LEFT) W.listen(item) })
        if (listening) Ui.box(x - 1.5f, y - 1.5f, w + 3f, 20f, 6.5f, 0, 1.5f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(x, y, w, 17f, 5f, if (over && !listening) 0x47000000 else 0x38000000, 0.5f, if (listening) Tok.alpha(Tok.a1, 0.55f) else Tok.LINE2)
        Ui.text(Type.small, "Key", x + 6.5f, y + 8.5f, Tok.T3)
        if (listening) {
            Ui.text(Type.label, "Press a key...", x + 33.5f, y + 8.5f, Tok.T1)
            return
        }
        val none = item.bind.key == -1
        Ui.keycap(x + 33.5f, y + 4f, if (none) "None" else Keys.name(item.bind), none = none)
        Ui.textR(Type.small, if (none) "click to set" else "click to change", x + w - 6.5f, y + 8.5f, Tok.T4)
    }

    override fun editor(item: Macro, x: Float, y: Float, w: Float, h: Float) {
        var yy = y
        W.field(field("name", 10, init = { item.id }) { item.id = it }, x, yy, w, "Name", 33f, "")
        yy += 21f
        when (item) {
            is ChatMacro -> W.field(field("text", init = { item.message }) { item.message = it }, x, yy, w, "Text", 33f, "/command or message")
            is ItemMacro -> {
                W.field(field("item", 64, ModernWindows.FieldStyle(suggest = { itemNames }), { item.item }) { item.item = it }, x, yy, w, "Item", 33f, "")
                yy += 21f
                yy += W.flabel("Use", x, yy) + 4f
                Ui.seg("mc:legit", x, yy, w, 18f, listOf("Rage", "Legit"), if (item.legit) 1 else 0) {
                    item.legit = it == 1
                    Sounds.SWITCH_RIGHT.play()
                }
            }
            is SettingMacro -> {
                W.field(field("module", 64, ModernWindows.FieldStyle(suggest = { Modules.all.map { it.id } }), { item.module }) { item.module = it },
                    x, yy, w, "Module", 33f, "")
                yy += 21f
                val settings = {
                    Modules.all.firstOrNull { it.id == item.module }?.settings
                        ?.filter { it !is GroupSetting && it.id != "enabled" && it.id != "bind" }?.map { it.id } ?: emptyList()
                }
                W.field(field("setting", 64, ModernWindows.FieldStyle(suggest = settings), { item.setting }) { item.setting = it },
                    x, yy, w, "Setting", 33f, "")
                yy += 21f
                val half = (w - 4f) / 2f
                W.field(field("press", init = { item.pressValue }) { item.pressValue = it }, x, yy, half, "Press", null, "")
                W.field(field("release", init = { item.releaseValue }) { item.releaseValue = it }, x + half + 4f, yy, half, "Release", null, "")
                yy += 21f
                Ui.seg("mc:hold", x, yy, w, 18f, listOf("Toggle", "Hold"), if (item.hold) 1 else 0) {
                    item.hold = it == 1
                    Sounds.SWITCH_RIGHT.play()
                }
            }
        }
        // its key, where there is room above the delete button (also set from the keycap in the list)
        yy += if (item is ChatMacro) 21f else 22f
        if (yy + 17f <= y + h - 18f - 4f) keyRow(item, x, yy, w)
        W.deleteButton("mc:" + System.identityHashCode(item), x, y + h - 18f, w, "Delete macro") {
            Macros.list.remove(item)
            if (W.listening === item) W.listening = null
            Sounds.DISABLE.play()
            W.notify("Macros", "deleted ${item.id}")
            select(Macros.list.firstOrNull(), true)
        }
    }
}

// ---- Homies ----

/**
 * One row per friend with their head (from minotar). A friend is
 * removed from their trash button, not by any click on their card, and the field on top adds one (Enter).
 */
internal object HomiesPane : Pane("homies", "Homies", "users", 360f, 236f, 150f, 180f) {
    val add = Ui.Field("", 16)
    private var scroll = 0f
    private var added: String? = null
    private var addedAt = 0L

    override val count get() = Friends.names.size
    override val hints = listOf("" to "not attacked, and shown as friends")

    override fun tools(right: Float, y: Float) {
        W.wbtn("hm:add", right - 15f, y, null, "plus", primary = true) { Ui.focused = add }
    }

    fun submit() {
        val name = add.text.trim()
        if (name.isEmpty()) return
        if (!Friends.isFriend(name)) Friends.add(name)
        add.text = ""
        added = name
        addedAt = System.currentTimeMillis()
        Sounds.ENABLE.play()
        W.notify("Homies", "$name is a homie now", NotificationType.SUCCESS)
    }

    override fun body(x: Float, y: Float, w: Float, h: Float) {
        W.field(add, x + 5f, y + 5f, w - 10f, "", 17f, "Add a homie by name")
        Ui.icon("plus", x + 5f + 6f, y + 5f + 4.75f, 7.5f, Tok.T3)
        val ly = y + 5f + 17f + 1f
        val lh = h - (ly - y)
        val names = Friends.names.toList()
        val maxScroll = max(0f, 3f + names.size * 23f - 1f + 5f - lh)
        scroll = scroll.coerceIn(0f, maxScroll)
        val sy = Ui.anim("hlist", scroll, 300)
        Ui.hit("hlist", x, ly, w, lh, scroll = { a -> scroll = (scroll - a.toFloat() * 23f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x, ly, w, lh)
        var ry = ly + 3f - sy
        for (name in names) {
            val rx = x + 4f
            val rw = w - 8f
            if (ry + 22f >= ly && ry <= ly + lh) row(name, rx, ry, rw)
            ry += 23f
        }
        if (names.isEmpty()) W.empty("users", "No homies yet", "Type a name above and press Enter.", x + w / 2f, ly + lh / 2f, w)
        Ui.popClip()
        if (maxScroll > 0f) ModernGui.scrollbar(x + w - 4f, ly + 1.5f, lh - 3f, sy, maxScroll)
    }

    private fun row(name: String, x: Float, y: Float, w: Float) {
        val e = if (name == added) Easing.OUT_EXPO.apply(((System.currentTimeMillis() - addedAt) / 450.0).coerceIn(0.0, 1.0)).toFloat() else 1f
        val pa = Canvas.alpha
        Canvas.alpha = pa * e
        val rx = x - (1f - e) * 5f
        val over = Ui.hit("hrow:$name", rx, y, w, 22f, pointer = false)
        if (over) Ui.box(rx, y, w, 22f, 5.5f, 0x0AFFFFFF)
        // the head, ringed, with a small shadow
        Ui.glow(rx + 4f, y + 6f, 14f, 14f, 3.5f, 3f, 0x66000000)
        Canvas.texturedRect(UrlTextures.get("https://minotar.net/helm/$name/32.png"), rx + 4f, y + 4f, 14f, 14f, 3.5f, 0f, 0f, 1f, -1, -1)
        Ui.box(rx + 4f, y + 4f, 14f, 14f, 3.5f, 0, 0.5f, 0x1FFFFFFF)
        Ui.text(Type.semi, Ui.fit(Type.semi, name, w - 23.5f - 20f), rx + 23.5f, y + 11f, Tok.T1)
        // the trash slides in on hover
        val t = Ui.anim("hrm:$name", if (over || Ui.hot("hrm:$name")) 1f else 0f, 300)
        if (t > 0.01f) {
            val bx = rx + w - 3f - 14f + (1f - t) * 3f
            val ro = Ui.hit("hrm:$name", bx, y + 4f, 14f, 14f, { b ->
                if (b != Keys.MOUSE_LEFT) return@hit
                Friends.remove(name)
                Sounds.DISABLE.play()
                W.notify("Homies", "removed $name")
            })
            val pb = Canvas.alpha
            Canvas.alpha = pb * t
            if (ro) Ui.box(bx, y + 4f, 14f, 14f, 4f, 0x1FFF4646)
            Ui.icon("trash", bx + 3.25f, y + 7.25f, 7.5f, if (ro) 0xFFFF7A7A.toInt() else Tok.T3)
            Canvas.alpha = pb
        }
        Canvas.alpha = pa
    }
}
