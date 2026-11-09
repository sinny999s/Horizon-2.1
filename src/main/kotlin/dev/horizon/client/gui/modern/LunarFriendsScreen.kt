package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.lunar.LunarFriendWire
import dev.horizon.client.lunar.LunarFriendWire.Friend
import dev.horizon.client.lunar.LunarFriendWire.Group
import dev.horizon.client.lunar.LunarFriendWire.Message
import dev.horizon.client.lunar.LunarFriendWire.Request
import dev.horizon.client.lunar.LunarFriends
import dev.horizon.client.lunar.LunarImages
import dev.horizon.client.lunar.LunarNameTag
import dev.horizon.client.lunar.LunarSettings
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
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
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Lunar Client's friends list and chats in the new GUI's style ([LunarFriends]). On the left,
 * friend requests (accept or deny; ones you sent can be cancelled), your group chats (+ makes one from friends you
 * pick), then your friends: pinned first, online with where they are and their Lunar logo and badge, offline ones with
 * when they were last seen (hover a friend to pin them). Pick a friend or group to see your messages on the right and
 * write to them, send stickers, and pin messages (hover one); Pins shows the pinned ones. A group can be renamed or
 * left. The top bar adds a friend by name, sets your status (online, away, busy, invisible) and whether players can
 * send you friend requests. Opened from the ClickGUI's Lunar menu.
 */
object LunarFriendsScreen : Screen(Component.literal("horizon-lunar-friends")) {
    const val W = 560f
    const val H = 330f
    private const val BAR = 29f
    private const val STATUS = 15f
    private const val LIST = 206f
    private const val ROW = 28f

    private val mc get() = Minecraft.getInstance()
    private var mouseX = 0f
    private var mouseY = 0f
    private var ox = 0f
    private var oy = 0f
    private var k = 1f
    private var closing = false
    private var scroll = 0f
    private var scrollMax = 0f
    private var chatScroll = 0f
    private var selected: UUID? = null
    private val add = Ui.Field(max = 16)
    private val message = Ui.Field(max = 256)

    /** Picking friends for a new group chat (null when not). */
    private var picking: MutableSet<UUID>? = null

    /** The open conversation shows its pinned messages instead. */
    private var showPins = false
    private var stickersOpen = false
    private var renaming = false

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
        Ui.animFrom("friends", 450, 0f)
        selected?.let { LunarFriends.load(it) }
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
        Ui.animFrom("friends", 160, 1f, Easing.IN_QUAD)
    }

    /** For ShotBot: opens [name]'s conversation (a friend's or a group's). */
    @JvmStatic
    fun select(name: String) {
        LunarFriends.friends.firstOrNull { it.name.equals(name, true) }?.let { pick(it.uuid); return }
        LunarFriends.groups.firstOrNull { it.name.equals(name, true) }?.let { pick(it.id) }
    }

    /** For ShotBot: shows the open conversation's pins, or the sticker picker. */
    @JvmStatic
    fun show(what: String) {
        when (what) {
            "pins" -> showPins = true
            "stickers" -> stickersOpen = true
            "pick" -> picking = LunarFriends.friends.take(2).map { it.uuid }.toMutableSet()
        }
    }

    private fun pick(key: UUID) {
        selected = key
        chatScroll = 0f
        message.text = ""
        showPins = false
        stickersOpen = false
        renaming = false
        LunarFriends.load(key)
    }

    /** One frame (from Overlay's screen layer). */
    fun draw() {
        val sw = width.toFloat()
        val sh = height.toFloat()
        k = min(1f, min((sw - 16f) / W, (sh - 16f) / H))
        ox = (sw - W * k) / 2f
        oy = (sh - H * k) / 2f
        val t = if (closing) Ui.anim("friends", 0f, 160, Easing.IN_QUAD) else Ui.anim("friends", 1f, 450)
        if (closing && t <= 0.02f) {
            closing = false
            super.onClose()
            return
        }
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
        Ui.block("friends", 0f, 0f, W, H)
        titleBar()
        list()
        chat()
        statusBar()
    }

    // ---- title bar: add a friend, your status, requests ----

    private val statuses = listOf(LunarFriendWire.ONLINE to "Online", LunarFriendWire.AWAY to "Away", LunarFriendWire.BUSY to "Busy",
        LunarFriendWire.INVISIBLE to "Invisible")

    private fun statusColor(s: Int) = when (s) {
        LunarFriendWire.AWAY -> 0xFFFFC94A.toInt()
        LunarFriendWire.BUSY -> 0xFFFF5A5A.toInt()
        LunarFriendWire.INVISIBLE -> 0xFF8A8A94.toInt()
        else -> 0xFF4ADE80.toInt()
    }

    private fun titleBar() {
        Ui.box(0f, BAR, W, 0.5f, 0f, Tok.LINE)
        Ui.glow(12.5f, 10.75f, 8f, 8f, 4f, 5f, Tok.alpha(Tok.a1, 0.3f))
        Ui.mark(9.5f, 7.75f, 14f)
        Type.brand.drawGradientMiddle("Friends", 28.5f, 14.75f, Tok.a1, Tok.a2)
        val vx = 28.5f + Type.brand.width("Friends") + 5f
        val vw = Type.ver.width("LUNAR") + 7f
        Ui.box(vx, 10.25f, vw, 9f, 3f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.textC(Type.ver, "LUNAR", vx + vw / 2f, 14.75f, Tok.T3)

        val fw = 160f
        val fx = 150f
        field(add, fx, 5.75f, fw, "plus", "Add a friend by name")
        Ui.button("addSend", fx + fw + 4f, 5.75f, 18f, "Add", null, accent = add.text.isNotBlank()) { sendAdd() }

        // Your status: click to go to the next one.
        val st = LunarFriends.status
        val label = statuses.firstOrNull { it.first == st }?.second ?: "Online"
        val sw = Type.small.width(label) + 20f
        val sx = W - 8.5f - 17f - 6f - 22f - 4f - sw
        val so = Ui.hit("status", sx, 6.25f, sw, 17f, { b ->
            if (b == Keys.MOUSE_LEFT) {
                val i = statuses.indexOfFirst { it.first == st }
                LunarFriends.setStatus(statuses[(i + 1) % statuses.size].first)
            }
        })
        Ui.box(sx, 6.25f, sw, 17f, 5f, if (so) Tok.FILL2 else Tok.FILL, 0.5f, Tok.LINE)
        Ui.box(sx + 6f, 12.75f, 5f, 5f, 2.5f, statusColor(st))
        Ui.text(Type.small, label, sx + 14f, 14.75f, if (so) Tok.T1 else Tok.T2)

        // Friend requests on or off.
        val rx = sx + sw + 4f
        val allow = LunarFriends.allowRequests
        val ro = Ui.hit("requests", rx, 6.25f, 22f, 17f, { if (it == Keys.MOUSE_LEFT) LunarFriends.setAllowRequests(!allow) })
        Ui.box(rx, 6.25f, 22f, 17f, 5f, if (ro) Tok.FILL2 else Tok.FILL, 0.5f, if (allow) Tok.LINE else Tok.alpha(0xFFFF5A5A.toInt(), 0.6f))
        Ui.icon(if (allow) "users" else "eyeoff", rx + 7f, 11f, 8f, if (allow) Tok.T2 else 0xFFFF8A8A.toInt())
        if (ro) Ui.textR(Type.tiny, if (allow) "taking friend requests" else "friend requests off", rx + 22f, BAR + 6f, Tok.T3)

        val cx = W - 8.5f - 17f
        val o = Ui.hit("close", cx, 6.25f, 17f, 17f, { if (it == Keys.MOUSE_LEFT) onClose() })
        if (o) Ui.box(cx, 6.25f, 17f, 17f, 5f, Tok.FILL2)
        Ui.icon("x", cx + 4.5f, 10.75f, 8f, if (o) Tok.T1 else Tok.T2)
    }

    private fun field(f: Ui.Field, x: Float, y: Float, w: Float, icon: String, placeholder: String) {
        val focusedNow = Ui.focused === f
        val over = Ui.hit(Ui.fieldId(f), x, y, w, 18f, { if (it == Keys.MOUSE_LEFT) Ui.focused = f })
        if (focusedNow) Ui.box(x - 2f, y - 2f, w + 4f, 22f, 7.5f, 0, 2f, Tok.alpha(Tok.a1, 0.14f))
        Ui.box(x, y, w, 18f, 5.5f, if (focusedNow) 0x59000000 else 0x40000000, 0.5f,
            if (focusedNow) Tok.alpha(Tok.a1, 0.55f) else if (over) 0x26FFFFFF else Tok.LINE2)
        Ui.icon(icon, x + 6f, y + 5f, 8f, Tok.T3)
        Ui.fieldText(f, x + 19f, y + 9f, w - 26f, Type.label, placeholder)
    }

    private fun sendAdd() {
        val n = add.text.trim()
        if (n.isEmpty()) return
        LunarFriends.sendRequest(n)
        add.text = ""
        Ui.focused = null
    }

    // ---- the list ----

    private sealed class Row {
        class Header(val text: String, val plus: Boolean = false) : Row()
        class Req(val r: Request) : Row()
        class Grp(val g: Group) : Row()
        class Pal(val f: Friend) : Row()
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        val reqs = LunarFriends.requests
        if (reqs.isNotEmpty() && picking == null) {
            out += Row.Header("REQUESTS  ${reqs.size}")
            reqs.sortedByDescending { it.inbound }.forEach { out += Row.Req(it) }
        }
        if (picking == null) {
            val groups = LunarFriends.groups
            out += Row.Header("GROUPS  ${groups.size}", plus = true)
            groups.forEach { out += Row.Grp(it) }
        }
        val all = LunarFriends.friends
        val online = all.filter { it.online }
        out += Row.Header(if (picking != null) "PICK FRIENDS FOR THE GROUP" else "ONLINE  ${online.size}")
        online.forEach { out += Row.Pal(it) }
        val offline = all.filter { !it.online }
        if (offline.isNotEmpty()) {
            out += Row.Header("OFFLINE  ${offline.size}")
            offline.forEach { out += Row.Pal(it) }
        }
        return out
    }

    private fun height(r: Row) = if (r is Row.Header) 16f else ROW

    private fun list() {
        Ui.box(LIST, BAR, 0.5f, H - BAR - STATUS, 0f, Tok.LINE)
        val gx = 6f
        val gy = BAR + 6f
        val gw = LIST - 12f
        val pickBar = if (picking != null) 22f else 0f
        val gh = H - STATUS - 6f - gy - pickBar
        if (!LunarFriends.signedIn) {
            val on = LunarSettings.network.value && LunarSettings.friends.value
            val lines = if (on) listOf("Connecting to Lunar...", "(Lunar connects once you join a server)")
            else listOf("Turn on Lunar and Friends in the", "ClickGUI's Lunar menu to see your friends")
            lines.forEachIndexed { i, s -> Ui.textC(Type.label, s, LIST / 2f, gy + gh / 2f - 5f + i * 10f, if (i == 0) Tok.T2 else Tok.T3) }
            return
        }
        val rows = rows()
        val total = rows.sumOf { height(it).toDouble() }.toFloat()
        scrollMax = max(0f, total - gh)
        scroll = scroll.coerceIn(0f, scrollMax)
        val sy = Ui.anim("friendsScroll", scroll, 300)
        Ui.hit("list", gx, gy, gw, gh, scroll = { a -> scroll = (scroll - a.toFloat() * ROW).coerceIn(0f, scrollMax); true }, pointer = false)
        Ui.pushClip(gx - 2f, gy, gw + 4f, gh)
        var y = gy - sy
        for (r in rows) {
            val h = height(r)
            if (y + h >= gy && y <= gy + gh) {
                when (r) {
                    is Row.Header -> {
                        Ui.text(Type.section, r.text, gx + 4f, y + 9f, Tok.T3)
                        if (r.plus && y >= gy) Ui.button("newGroup", gx + gw - 15f, y + 2f, 12f, null, "plus") {
                            picking = HashSet()
                            selected = null
                        }
                    }
                    is Row.Req -> request(r.r, gx, y, gw, gy, gy + gh)
                    is Row.Grp -> group(r.g, gx, y, gw, gy, gy + gh)
                    is Row.Pal -> friend(r.f, gx, y, gw, gy, gy + gh)
                }
            }
            y += h
        }
        Ui.popClip()
        if (LunarFriends.friends.isEmpty() && LunarFriends.requests.isEmpty() && LunarFriends.groups.isEmpty()) {
            Ui.textC(Type.label, "No Lunar friends yet: add one above", LIST / 2f, gy + gh / 2f + 10f, Tok.T3)
        }
        if (scrollMax > 0f) {
            val bh = max(20f, gh * gh / (gh + scrollMax))
            Ui.box(LIST - 4.5f, gy + (gh - bh) * (sy / scrollMax), 2f, bh, 1f, 0x26FFFFFF)
        }
        picking?.let { p ->
            val by = H - STATUS - 4f - 18f
            val cw = Ui.button("pickCancel", gx, by, 18f, "Cancel", "x") { picking = null }
            Ui.button("pickCreate", gx + cw + 4f, by, 18f, if (p.isEmpty()) "Pick friends" else "Make group (${p.size})", "users", accent = p.isNotEmpty()) {
                if (p.isNotEmpty()) {
                    LunarFriends.createGroup(p.toList()) { id -> pick(id) }
                    picking = null
                }
            }
        }
    }

    private fun icon(logo: Int, plus: Int, badge: Int, x: Float, y: Float, online: Boolean) {
        LunarNameTag.canvasLogo(x, y, 14f, if (online) logo else Colors.withAlpha(logo, 0.35f), if (online) plus else 0)
        if (badge != 0) LunarNameTag.canvasBadge(badge, x + 8f, y + 8f, 8f)
    }

    private fun friend(f: Friend, x: Float, y: Float, w: Float, top: Float, bottom: Float) {
        val y0 = max(y, top)
        val y1 = min(y + ROW - 2f, bottom)
        val p = picking
        val over = y1 > y0 && Ui.hit("friend:${f.uuid}", x, y0, w, y1 - y0, { b ->
            if (b == Keys.MOUSE_LEFT) {
                if (p != null) { if (!p.add(f.uuid)) p.remove(f.uuid) } else pick(f.uuid)
            }
        })
        val on = if (p != null) f.uuid in p else selected == f.uuid
        if (on || over) Ui.box(x, y, w, ROW - 2f, 6f, if (on) Tok.FILL2 else Tok.FILL, 0.5f, if (on) Tok.alpha(Tok.a1, 0.6f) else Tok.LINE)
        icon(f.logo, f.plus, f.badge, x + 6f, y + 6f, f.online)
        if (f.online) Ui.box(x + 16.5f, y + 4.5f, 5f, 5f, 2.5f, statusColor(f.status), 1f, 0xFF15121A.toInt())
        val tx = x + 27f
        val pinned = LunarFriends.isPinned(f.uuid)
        val nameW = Type.semi.width(f.name)
        Ui.text(Type.semi, Ui.fit(Type.semi, f.name, w - 50f), tx, y + 9.5f, if (f.online) Tok.T1 else Tok.T2)
        if (pinned) Ui.icon("pin", tx + min(nameW, w - 50f) + 3f, y + 6f, 6f, Tok.light(Tok.a1, 0.3f))
        Ui.text(Type.small, Ui.fit(Type.small, where(f), w - 33f), tx, y + 18.5f, Tok.T3)
        if (p != null) {
            Ui.box(x + w - 16f, y + 8f, 10f, 10f, 3f, if (on) Tok.a1 else Tok.FILL, 0.5f, Tok.LINE2)
            if (on) Ui.icon("check", x + w - 14.5f, y + 9.5f, 7f, Tok.WHITE)
            return
        }
        if (f.uuid in LunarFriends.unread) Ui.dbox(x + w - 10f, y + 10f, 6f, 6f, 3f, Tok.a1, Tok.a2)
        else if (over && y >= top && y + ROW <= bottom) {
            Ui.button("pin:${f.uuid}", x + w - 17f, y + 6.5f, 13f, null, "pin", active = pinned) { LunarFriends.togglePin(f) }
        }
    }

    private fun group(g: Group, x: Float, y: Float, w: Float, top: Float, bottom: Float) {
        val y0 = max(y, top)
        val y1 = min(y + ROW - 2f, bottom)
        val over = y1 > y0 && Ui.hit("group:${g.id}", x, y0, w, y1 - y0, { if (it == Keys.MOUSE_LEFT) pick(g.id) })
        val on = selected == g.id
        if (on || over) Ui.box(x, y, w, ROW - 2f, 6f, if (on) Tok.FILL2 else Tok.FILL, 0.5f, if (on) Tok.alpha(Tok.a1, 0.6f) else Tok.LINE)
        Ui.box(x + 5f, y + 5f, 16f, 16f, 5f, Tok.alpha(Tok.a1, 0.2f))
        Ui.icon("users", x + 9f, y + 9f, 8f, Tok.T1)
        Ui.text(Type.semi, Ui.fit(Type.semi, g.name, w - 40f), x + 27f, y + 9.5f, Tok.T1)
        Ui.text(Type.small, "${g.members.size} members", x + 27f, y + 18.5f, Tok.T3)
        if (g.id in LunarFriends.unread) Ui.dbox(x + w - 10f, y + 10f, 6f, 6f, 3f, Tok.a1, Tok.a2)
    }

    /** Where a friend is: their server (and version), else when they were last on. */
    private fun where(f: Friend): String = when {
        f.online -> (f.location ?: "Online") + (f.version?.let { "  $it" } ?: "") + when (f.status) {
            LunarFriendWire.AWAY -> "  (away)"
            LunarFriendWire.BUSY -> "  (busy)"
            else -> ""
        }
        f.lastSeen > 0 -> "Last seen " + ago(f.lastSeen)
        else -> "Offline"
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

    private fun request(r: Request, x: Float, y: Float, w: Float, top: Float, bottom: Float) {
        Ui.box(x, y, w, ROW - 2f, 6f, Tok.FILL, 0.5f, Tok.LINE)
        Ui.icon("users", x + 8f, y + 9f, 9f, Tok.T2)
        val tx = x + 25f
        Ui.text(Type.semi, Ui.fit(Type.semi, r.name, w - 90f), tx, y + 9.5f, Tok.T1)
        Ui.text(Type.small, if (r.inbound) "wants to be friends" else "request sent", tx, y + 18.5f, Tok.T3)
        if (y < top || y + ROW > bottom) return
        if (r.inbound) {
            Ui.button("deny:${r.uuid}", x + w - 22f, y + 6.5f, 13f, null, "x") { LunarFriends.deny(r) }
            Ui.button("accept:${r.uuid}", x + w - 40f, y + 6.5f, 13f, null, "check", accent = true) { LunarFriends.accept(r) }
        } else {
            Ui.button("cancel:${r.uuid}", x + w - 22f, y + 6.5f, 13f, null, "x") { LunarFriends.cancel(r) }
        }
    }

    // ---- the conversation ----

    private fun chat() {
        val x0 = LIST + 0.5f
        val cw = W - x0
        val key = selected
        val f = key?.let { LunarFriends.friend(it) }
        val g = key?.let { LunarFriends.group(it) }
        if (key == null || (f == null && g == null)) {
            Ui.icon("chat", x0 + cw / 2f - 10f, BAR + (H - BAR - STATUS) / 2f - 22f, 20f, Tok.T4)
            val hint = when {
                picking != null -> "Pick friends on the left, then Make group"
                LunarFriends.signedIn -> "Pick a friend or group to message them"
                else -> "Your Lunar friends show here"
            }
            Ui.textC(Type.label, hint, x0 + cw / 2f, BAR + (H - BAR - STATUS) / 2f + 6f, Tok.T3)
            return
        }
        LunarFriends.unread -= key
        // Header: who and where (or the group's members).
        val hy = BAR
        if (f != null) {
            icon(f.logo, f.plus, f.badge, x0 + 12f, hy + 9f, f.online)
            Ui.text(Type.name, f.name, x0 + 34f, hy + 12f, Tok.T1)
            Ui.text(Type.small, Ui.fit(Type.small, where(f), cw - 200f), x0 + 34f, hy + 22f, Tok.T3)
        } else if (g != null) {
            Ui.box(x0 + 10f, hy + 8f, 17f, 17f, 5f, Tok.alpha(Tok.a1, 0.2f))
            Ui.icon("users", x0 + 14.5f, hy + 12.5f, 8f, Tok.T1)
            Ui.text(Type.name, Ui.fit(Type.name, g.name, cw - 230f), x0 + 34f, hy + 12f, Tok.T1)
            Ui.text(Type.small, Ui.fit(Type.small, g.members.values.joinToString(", "), cw - 230f), x0 + 34f, hy + 22f, Tok.T3)
        }
        // Header buttons, right to left.
        var bx = W - 10f
        fun headerButton(id: String, label: String, icon: String, active: Boolean = false, click: () -> Unit) {
            val w = Type.small.width(label) + 16f
            bx -= w
            Ui.button(id, bx, hy + 9f, 15f, label, icon, active = active, click = click)
            bx -= 4f
        }
        if (f != null) headerButton("remove", "Remove", "trash") { LunarFriends.remove(f); selected = null }
        if (g != null) {
            headerButton("leave", "Leave", "x") { LunarFriends.leaveGroup(g); selected = null }
            headerButton("rename", "Rename", "config", active = renaming) {
                renaming = !renaming
                message.text = if (renaming) g.name else ""
                Ui.focused = message
            }
        }
        val pinCount = LunarFriends.pinned(key).size
        headerButton("pins", if (pinCount > 0) "Pins $pinCount" else "Pins", "pin", active = showPins) { showPins = !showPins; chatScroll = 0f }
        Ui.box(x0, hy + 32f, cw, 0.5f, 0f, Tok.LINE)

        // Messages, newest at the bottom.
        val mx = x0 + 12f
        val mw = cw - 24f
        val top = hy + 38f
        val inputY = H - STATUS - 26f
        val bottom = inputY - 6f - (if (stickersOpen) 58f else 0f)
        val self = mc.user.profileId
        val msgs = if (showPins) LunarFriends.pinned(key) else LunarFriends.conversation(key)

        /** One line: a message's text (its first line [head] has the time and pin), or a group sender's name ([name]). */
        class Line(val msg: Message, val text: String, val mine: Boolean, val head: Boolean, val time: String?, val h: Float, val name: Boolean = false)
        val lines = ArrayList<Line>()
        val fmt = SimpleDateFormat("HH:mm")
        for (m in msgs) {
            val mine = m.sender == self
            val time = if (m.at > 0) fmt.format(Date(m.at)) else null
            // In a group, others' messages start with who wrote them.
            if (g != null && !mine && !m.system) lines += Line(m, m.senderName, false, false, null, 9f, name = true)
            val sticker = m.sticker
            if (sticker != null) {
                lines += Line(m, "", mine, true, time, 36f)
                continue
            }
            val wrapped = Ui.wrap(Type.body, m.text, mw * 0.7f, 20)
            wrapped.forEachIndexed { i, s -> lines += Line(m, s, mine, i == 0, if (i == 0) time else null, 10f) }
        }
        val total = lines.sumOf { it.h.toDouble() }.toFloat() + msgs.size * 4f
        val maxScroll = max(0f, total - (bottom - top))
        chatScroll = chatScroll.coerceIn(0f, maxScroll)
        Ui.hit("chatScroll", x0, top, cw, bottom - top, scroll = { a -> chatScroll = (chatScroll + a.toFloat() * 20f).coerceIn(0f, maxScroll); true }, pointer = false)
        Ui.pushClip(x0, top, cw, bottom - top)
        if (msgs.isEmpty()) Ui.textC(Type.label, if (showPins) "No pinned messages" else "No messages yet", x0 + cw / 2f, (top + bottom) / 2f, Tok.T3)
        var y = bottom - total + chatScroll
        var last: Message? = null
        for (l in lines) {
            if (l.msg !== last) {
                y += 4f
                last = l.msg
            }
            if (y + l.h >= top && y <= bottom) {
                if (l.name) Ui.text(Type.tiny, l.text, mx + 2f, y + 4f, Tok.T3)
                else drawLine(key, l.msg, l.text, l.mine, l.time, l.h, mx, mw, y, top, bottom, l.head)
            }
            y += l.h
        }
        Ui.popClip()

        if (stickersOpen) stickerPicker(key, mx, bottom + 4f, mw)

        // Write to them (or the group's new name).
        val bw = Type.small.width("Send") + 9f
        val sbw = 18f
        field(message, mx, inputY, mw - bw - sbw - 8f, if (renaming) "config" else "chat",
            if (renaming) "New name for the group" else "Message ${f?.name ?: g?.name}")
        Ui.button("stickers", mx + mw - bw - sbw - 4f, inputY, 18f, null, "fav", active = stickersOpen) { stickersOpen = !stickersOpen }
        Ui.button("send", mx + mw - bw, inputY, 18f, if (renaming) "Save" else "Send", null, accent = message.text.isNotBlank()) { send() }
    }

    /** One line of a message (or the sender's name above one in a group); a sticker as its picture. */
    private fun drawLine(key: UUID, m: Message, text: String, mine: Boolean, time: String?, h: Float, mx: Float, mw: Float, y: Float,
                         top: Float, bottom: Float, pinnable: Boolean) {
        if (m.system) {
            Ui.textC(Type.tiny, text, mx + mw / 2f, y + h / 2f - 1f, Tok.T4)
            return
        }
        val sticker = m.sticker
        val tw = if (sticker != null) 32f else Type.body.width(text)
        val bx = if (mine) mx + mw - tw - 8f else mx
        if (sticker != null) {
            val tex = sticker.url.takeIf { it.isNotEmpty() }?.let { LunarImages.get(it) }
            if (tex != null) Canvas.texture(mc.textureManager.getTexture(tex).textureView, bx + 4f, y, 32f, 32f, -1, -1, -1, -1)
            else {
                Ui.box(bx, y, 40f, 32f, 5f, Tok.FILL2)
                Ui.textC(Type.tiny, Ui.fit(Type.tiny, sticker.name.ifEmpty { "sticker" }, 36f), bx + 20f, y + 16f, Tok.T2)
            }
        } else {
            Ui.box(bx, y - 1f, tw + 8f, h, 3.5f, if (mine) Tok.alpha(Tok.a1, 0.22f) else Tok.FILL2)
            Ui.text(Type.body, text, bx + 4f, y + h / 2f - 1f, Tok.T1)
        }
        val pinned = m.pinned || LunarFriends.pinned(key).any { it.id != null && it.id == m.id }
        val right = bx + (if (sticker != null) 40f else tw + 8f)
        if (time != null) {
            if (mine) Ui.textR(Type.tiny, time, bx - 4f, y + 4f, Tok.T4) else Ui.text(Type.tiny, time, right + 4f, y + 4f, Tok.T4)
        }
        if (pinned && pinnable) Ui.icon("pin", if (mine) bx - 26f else right + 26f, y + 1f, 6f, Tok.light(Tok.a1, 0.3f))
        // Hover a message to pin or unpin it.
        if (pinnable && m.id != null && y >= top && y + 12f <= bottom) {
            val hx = if (mine) bx - 44f else right + 36f
            val rowOver = Ui.my >= y - 1f && Ui.my <= y + h && Ui.mx >= mx && Ui.mx <= mx + mw
            if (rowOver) Ui.button("pinmsg:${m.id}", hx, y - 1f, 11f, null, "pin", active = pinned) { LunarFriends.togglePinned(key, m) }
        }
    }

    private fun stickerPicker(key: UUID, x: Float, y: Float, w: Float) {
        Ui.box(x, y, w, 54f, 6f, Tok.FILL, 0.5f, Tok.LINE)
        val stickers = LunarFriends.stickers
        if (stickers.isEmpty()) {
            Ui.textC(Type.label, "No Lunar stickers yet", x + w / 2f, y + 27f, Tok.T3)
            return
        }
        var sx = x + 5f
        for (s in stickers) {
            if (sx + 44f > x + w) break
            val over = Ui.hit("sticker:${s.id}", sx, y + 5f, 44f, 44f, { b ->
                if (b == Keys.MOUSE_LEFT) {
                    LunarFriends.sendSticker(key, s)
                    stickersOpen = false
                }
            })
            if (over) Ui.box(sx, y + 5f, 44f, 44f, 5f, Tok.FILL2)
            val tex = s.url.takeIf { it.isNotEmpty() }?.let { LunarImages.get(it) }
            if (tex != null) Canvas.texture(mc.textureManager.getTexture(tex).textureView, sx + 6f, y + 8f, 32f, 32f, -1, -1, -1, -1)
            else Ui.textC(Type.tiny, Ui.fit(Type.tiny, s.name, 40f), sx + 22f, y + 27f, Tok.T2)
            sx += 48f
        }
    }

    private fun send() {
        val key = selected ?: return
        val t = message.text.trim()
        if (t.isEmpty()) return
        if (renaming) {
            LunarFriends.group(key)?.let { LunarFriends.renameGroup(it, t) }
            renaming = false
        } else LunarFriends.send(key, t)
        message.text = ""
        chatScroll = 0f
    }

    // ---- status bar ----

    private fun statusBar() {
        val y = H - STATUS
        Ui.box(0f, y, W, 0.5f, 0f, Tok.LINE)
        val all = LunarFriends.friends
        val left = if (!LunarFriends.signedIn) "Not signed in to Lunar" else "${all.count { it.online }} of ${all.size} online"
        Ui.text(Type.status, left, 10f, y + STATUS / 2f, Tok.T3)
        val share = LunarSettings.shareServer.value
        Ui.textR(Type.status, if (share) "Your friends see which server you're on" else "Your server is hidden from friends", W - 10f, y + STATUS / 2f, Tok.T3)
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
                picking != null -> picking = null
                stickersOpen -> stickersOpen = false
                else -> onClose()
            }
            return true
        }
        if (f != null) {
            when {
                key == Keys.ENTER && f === add -> sendAdd()
                key == Keys.ENTER && f === message -> send()
                key == Keys.TAB -> Ui.focused = null
                else -> f.key(key)
            }
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val s = event.codepointAsString()
        // Typing with nothing focused writes to the open conversation.
        if (s.isNotBlank() && Ui.focused == null && selected != null) Ui.focused = message
        Ui.focused?.let { f -> s.forEach { f.type(it) } }
        return true
    }

    /** For ShotBot: types [text] into the open conversation and sends it. */
    @JvmStatic
    fun testSend(text: String) {
        message.text = text
        send()
    }
}
