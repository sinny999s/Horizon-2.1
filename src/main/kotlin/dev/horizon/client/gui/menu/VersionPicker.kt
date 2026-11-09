package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.compat.Via
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.SharedConstants
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.abs
import kotlin.math.floor

/**
 * ViaFabricPlus's version choice ([Via]) as a popup over the Multiplayer screen, in place of its own
 * screen: for all servers, or for one [server] (right-clicking it), where "Default" leaves it to the all-servers choice.
 * Versions are grouped as ViaFabricPlus's tabs group them (modern, legacy, beta, alpha, classic, April Fools); typing
 * searches, Enter takes the best match, a click outside or Esc closes it.
 */
class VersionPicker(
    private val server: String?,
    private val current: Any?,
    private val choose: (Any?) -> Unit,
    private val close: () -> Unit,
) : MenuPopup {
    /** A row: a section title, or a version (null being "Default"). */
    private class Row(val title: String?, val version: Any?, val label: String, val note: String)

    private val versions = Via.versions()
    private val native = SharedConstants.getCurrentVersion().protocolVersion()
    private val enter = Animation(Easing.OUT_CUBIC, 200)
    private var query = ""
    private var rows = build()
    private var scroll = 0f
    private var scrollTarget = -1f

    // Where things were in the last frame.
    private var px = 0f
    private var py = 0f
    private var pw = 0f
    private var ph = 0f
    private var listY = 0f
    private var listH = 0f

    init {
        enter.reset(0f)
    }

    private fun build(): List<Row> {
        val q = query.trim().lowercase()
        fun matches(label: String) = q.isEmpty() || label.lowercase().contains(q)
        val out = ArrayList<Row>()
        if (server != null && matches("Default")) out += Row(null, null, "Default", Via.name(Via.target()))
        Via.autoDetect?.let { if (matches("Auto detect")) out += Row(null, it, "Auto detect", "Pings the server first") }
        for ((section, types) in SECTIONS) {
            val inSection = versions.filter { Via.type(it) in types && matches(Via.name(it)) }
            if (inSection.isEmpty()) continue
            out += Row(section, null, "", "")
            for (v in inSection) out += Row(null, v, Via.name(v), if (Via.protocol(v) == native) "This client" else "")
        }
        return out
    }

    private fun height(r: Row) = if (r.title != null) 18f else 22f

    override fun draw(w: Float, h: Float, mouseX: Float, mouseY: Float) {
        enter.update(1f)
        val k = enter.value
        Canvas.alpha = k
        Canvas.quad(0f, 0f, w, h, 0xA0000000.toInt())
        pw = minOf(300f, w - 40f)
        ph = minOf(380f, h - 50f)
        px = floor((w - pw) / 2f)
        py = floor((h - ph) / 2f + (1f - k) * 10f)
        Menus.glass(px, py, pw, ph, 8f, 0xFF2C2C2C.toInt(), 0x40FFFFFF)

        val title = if (server == null) "Join servers as" else "Join ${Menus.fit(server, Fonts.semibold12, pw - 110f)} as"
        Fonts.semibold12.draw(title, px + 12f, py + 11f, Theme.text)
        val sub = if (server == null) "Servers with their own version keep it" else "Right-click a server to change it again"
        Fonts.medium65.draw(sub, px + 12f, py + 26f, Menus.GREY)

        // The search field.
        val sy = py + 40f
        Menus.glass(px + 10f, sy, pw - 20f, 16f, 5f, 0xFF3A3A3A.toInt(), 0x30FFFFFF)
        if (query.isEmpty()) Fonts.medium75.drawMiddle("Search versions", px + 17f, sy + 8f, 0x80FFFFFF.toInt())
        else Fonts.medium75.drawMiddle(Menus.fit(query, Fonts.medium75, pw - 34f), px + 17f, sy + 8f, Theme.text)
        if (System.currentTimeMillis() / 500 % 2 == 0L) {
            Menus.rect(px + 17f + minOf(Fonts.medium75.width(query), pw - 34f) + 0.5f, sy + 4f, 1f, 8f, 0f, 0xC0FFFFFF.toInt())
        }

        listY = sy + 22f
        listH = py + ph - 8f - listY
        val lx = px + 8f
        val lw = pw - 16f
        var content = 0f
        for (r in rows) content += height(r) + 2f
        val max = maxOf(0f, content - listH)
        if (scrollTarget < 0f) {
            // Opened: start with the current choice in the middle.
            var y = 0f
            for (r in rows) {
                if (r.title == null && r.version === current) break
                y += height(r) + 2f
            }
            scrollTarget = y - listH / 2f
            scroll = scrollTarget.coerceIn(0f, max)
        }
        scrollTarget = scrollTarget.coerceIn(0f, max)
        scroll = Fps.smooth(scroll, scrollTarget, 18f)
        if (abs(scroll - scrollTarget) < 0.1f) scroll = scrollTarget

        Canvas.pushScissor(lx - 2f, listY, lx + lw + 2f, listY + listH)
        var y = listY - scroll
        for (r in rows) {
            val rh = height(r)
            if (y + rh >= listY && y <= listY + listH) {
                if (r.title != null) {
                    Fonts.medium65.draw(r.title.uppercase(), lx + 6f, y + 7f, 0xFF7A7A7A.toInt())
                } else {
                    val selected = r.version === current
                    val hovered = mouseY >= listY && mouseY <= listY + listH && mouseX >= lx && mouseX <= lx + lw && mouseY >= y && mouseY <= y + rh
                    if (hovered) Cursor.request(CursorTypes.POINTING_HAND)
                    if (selected || hovered) Menus.card(lx, y, lw, rh, hovered, selected)
                    Fonts.medium8.drawMiddle(r.label, lx + 8f, y + rh / 2f, if (selected) Theme.text else 0xFFD0D0D0.toInt())
                    if (r.note.isNotEmpty()) {
                        val nw = Fonts.medium65.width(r.note)
                        Fonts.medium65.drawMiddle(r.note, lx + lw - 8f - nw, y + rh / 2f, if (selected) Menus.ACCENT else 0xFF7A7A7A.toInt())
                    }
                }
            }
            y += rh + 2f
        }
        Canvas.popScissor()
        if (rows.isEmpty()) Fonts.medium75.drawMiddleCentered("No versions match", px + pw / 2f, listY + 20f, Menus.GREY)
        if (max > 0f) {
            val barH = maxOf(16f, listH * listH / content)
            Menus.rect(px + pw - 5f, listY + (listH - barH) * (scroll / max), 2f, barH, 1f, 0x50FFFFFF)
        }
        Canvas.alpha = 1f
    }

    override fun mouseClicked(event: MouseButtonEvent) {
        val x = event.x().toFloat()
        val y = event.y().toFloat()
        if (x < px || x > px + pw || y < py || y > py + ph) {
            Menus.back()
            close()
            return
        }
        if (event.button() != Keys.MOUSE_LEFT || y < listY || y > listY + listH) return
        var ry = listY - scroll
        for (r in rows) {
            val rh = height(r)
            if (y >= ry && y <= ry + rh) {
                if (r.title == null) pick(r.version)
                return
            }
            ry += rh + 2f
        }
    }

    private fun pick(version: Any?) {
        Menus.toggle(true)
        close()
        choose(version)
    }

    override fun mouseScrolled(by: Float) {
        scrollTarget -= by * 28f
    }

    override fun keyPressed(event: KeyEvent) {
        when (event.key()) {
            Keys.ESCAPE -> {
                Menus.back()
                close()
            }
            Keys.BACKSPACE -> if (query.isNotEmpty()) search(query.dropLast(1))
            Keys.ENTER -> best()?.let { pick(it.version) }
        }
    }

    override fun charTyped(text: String) = search(query + text)

    /** What Enter takes: the first version starting with the search ("1.8" is 1.8.x, not 1.21.7-1.21.8), else the first. */
    private fun best(): Row? {
        val versions = rows.filter { it.title == null }
        val q = query.trim().lowercase()
        return versions.firstOrNull { q.isNotEmpty() && it.label.lowercase().startsWith(q) } ?: versions.firstOrNull()
    }

    private fun search(q: String) {
        query = q
        rows = build()
        scrollTarget = 0f
    }

    private companion object {
        /** ViaFabricPlus's tabs and the ViaVersion version types in each. */
        val SECTIONS = listOf(
            "Modern" to setOf("RELEASE"),
            "Legacy" to setOf("RELEASE_INITIAL"),
            "Beta" to setOf("BETA_INITIAL", "BETA_LATER"),
            "Alpha" to setOf("ALPHA_INITIAL", "ALPHA_LATER"),
            "Classic" to setOf("CLASSIC"),
            "April Fools" to setOf("SPECIAL"),
        )
    }
}
