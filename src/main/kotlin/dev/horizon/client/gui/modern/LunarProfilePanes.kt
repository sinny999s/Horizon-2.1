package dev.horizon.client.gui.modern

import dev.horizon.client.gui.Keys
import dev.horizon.client.lunar.LunarBadges
import dev.horizon.client.lunar.LunarNameTag
import dev.horizon.client.lunar.LunarProfileEdits
import dev.horizon.client.lunar.LunarSettings
import dev.horizon.client.lunar.LunarWire
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * The locker's Outfits and Badges tabs ([LunarProfileEdits]).
 * - Outfits: your Lunar outfits; the one you wear is marked. Pick one to wear it, rename or delete it, or have it worn
 *   instead in a situation (on this server, in this dimension, at night, in rain, while swimming); those rules are
 *   listed under it. New makes an outfit (named from the box at the top).
 * - Badges: the badges you own (and None); click one to wear it. Under them, the Lunar+ colours Lunar offers you.
 */
internal object LunarProfilePanes {
    private val mc get() = Minecraft.getInstance()

    private fun notSignedIn(gx: Float, gy: Float, gw: Float, gh: Float): Boolean {
        if (testing || (LunarSettings.network.value && dev.horizon.client.lunar.LunarNetwork.state == 3)) return false
        Ui.textC(Type.label, "Connect to Lunar to change your profile", gx + gw / 2f, gy + gh / 2f - 5f, Tok.T2)
        Ui.textC(Type.small, "(Lunar services on, and join a server)", gx + gw / 2f, gy + gh / 2f + 6f, Tok.T3)
        return true
    }

    /** ShotBot shows the panes without a connection. */
    @Volatile
    var testing = false

    // ---- outfits ----

    fun outfits(gx: Float, gy: Float, gw: Float, gh: Float, name: Ui.Field, picked: UUID?, pick: (UUID?) -> Unit) {
        if (notSignedIn(gx, gy, gw, gh)) return
        val outfits = LunarProfileEdits.outfits()
        val tree = LunarProfileEdits.tree()
        val wearing = tree.default ?: outfits.firstOrNull()?.id
        val sel = outfits.firstOrNull { it.id == picked } ?: outfits.firstOrNull { it.id == wearing }
        // Left: the outfits.
        val lw = gw * 0.48f
        Ui.text(Type.section, "OUTFITS  ${outfits.size}", gx + 2f, gy + 4f, Tok.T4)
        Ui.button("outfit:new", gx + lw - 40f, gy - 2f, 12f, "New", "plus") {
            LunarProfileEdits.create(name.text)
            name.text = ""
        }
        var y = gy + 13f
        for (o in outfits) {
            if (y + 24f > gy + gh) break
            val on = o.id == sel?.id
            val over = Ui.hit("outfit:${o.id}", gx, y, lw, 22f, { if (it == Keys.MOUSE_LEFT) pick(o.id) })
            Ui.box(gx, y, lw, 22f, 5f, if (on) Tok.FILL2 else if (over) Tok.FILL else 0, 0.5f, if (on) Tok.alpha(Tok.a1, 0.6f) else Tok.LINE)
            Ui.text(Type.semi, Ui.fit(Type.semi, o.name.ifEmpty { "Outfit" }, lw - 60f), gx + 6f, y + 7.5f, Tok.T1)
            Ui.text(Type.tiny, "${o.cosmetics.size} cosmetics", gx + 6f, y + 15.5f, Tok.T3)
            if (o.id == wearing) {
                val cw = Type.chip.width("Wearing") + 8f
                Ui.box(gx + lw - cw - 5f, y + 6.5f, cw, 9f, 3.5f, Tok.alpha(Tok.a1, 0.25f))
                Ui.textC(Type.chip, "Wearing", gx + lw - cw / 2f - 5f, y + 11f, Tok.light(Tok.a1, 0.35f))
            }
            y += 25f
        }
        if (outfits.isEmpty()) Ui.text(Type.label, "No outfits yet: New makes one", gx + 4f, gy + 24f, Tok.T3)

        // Right: the picked outfit.
        val rx = gx + lw + 10f
        val rw = gw - lw - 10f
        Ui.box(rx - 5f, gy, 0.5f, gh, 0f, Tok.LINE)
        if (sel == null) return
        Ui.text(Type.name, Ui.fit(Type.name, sel.name.ifEmpty { "Outfit" }, rw), rx, gy + 5f, Tok.T1)
        var by = gy + 14f
        var bx = rx
        fun button(id: String, label: String, icon: String, accent: Boolean = false, click: () -> Unit) {
            bx += Ui.button("outfit:$id", bx, by, 14f, label, icon, accent = accent, click = click) + 4f
        }
        if (sel.id != wearing) button("wear", "Wear", "check", accent = true) { LunarProfileEdits.wear(sel) }
        button("rename", "Rename", "config") {
            LunarProfileEdits.rename(sel, name.text)
            name.text = ""
        }
        if (sel.id != wearing) button("delete", "Delete", "trash") { LunarProfileEdits.delete(sel) }
        by += 22f
        Ui.text(Type.section, "WEAR IT INSTEAD", rx, by + 3f, Tok.T4)
        by += 10f
        // Quick conditions from where you are now.
        val level = mc.level
        val server = mc.currentServer?.ip?.substringBefore(':')
        val quick = buildList {
            if (server != null) add("On this server" to LunarWire.Condition(LunarWire.Condition.SERVER, 0, server))
            if (level != null) add("In this dimension" to LunarWire.Condition(LunarWire.Condition.DIMENSION, 0, level.dimension().identifier().toString()))
            add("At night" to LunarWire.Condition(LunarWire.Condition.TIME, 2))
            add("In rain" to LunarWire.Condition(LunarWire.Condition.WEATHER, 1))
            add("While swimming" to LunarWire.Condition(LunarWire.Condition.MOVEMENT, 1))
        }
        bx = rx
        for ((label, c) in quick) {
            val w = Type.small.width(label) + 9f
            if (bx + w > rx + rw) {
                bx = rx
                by += 17f
            }
            bx += Ui.button("rule:$label", bx, by, 13f, label, null) { LunarProfileEdits.addRule(c, sel) } + 3f
        }
        by += 20f
        Ui.text(Type.section, "RULES (FIRST THAT HOLDS)", rx, by + 3f, Tok.T4)
        by += 10f
        if (tree.rules.isEmpty()) Ui.text(Type.small, "None: you always wear the marked outfit", rx, by + 5f, Tok.T3)
        for ((i, r) in tree.rules.withIndex()) {
            if (by + 14f > gy + gh) break
            val oname = outfits.firstOrNull { it.id == r.second }?.name ?: "?"
            Ui.text(Type.small, Ui.fit(Type.small, "$oname ${LunarProfileEdits.conditionText(r.first)}", rw - 20f), rx + 2f, by + 6f, Tok.T2)
            Ui.button("rule:x:$i", rx + rw - 13f, by, 12f, null, "x") { LunarProfileEdits.removeRule(i) }
            by += 14f
        }
    }

    // ---- badges and Lunar+ colour ----

    fun badges(gx: Float, gy: Float, gw: Float, gh: Float, search: String) {
        if (notSignedIn(gx, gy, gw, gh)) return
        val q = search.trim().lowercase()
        val ids = listOf(0) + LunarProfileEdits.badges().filter { id ->
            q.isEmpty() || (LunarBadges.badge(id)?.name?.lowercase()?.contains(q) ?: false)
        }
        val worn = LunarProfileEdits.badge
        Ui.text(Type.section, "BADGE BY YOUR NAME", gx + 2f, gy + 4f, Tok.T4)
        val tw = 52f
        val cols = ((gw + 6f) / (tw + 6f)).toInt().coerceAtLeast(1)
        var i = 0
        val top = gy + 12f
        for (id in ids) {
            val x = gx + (i % cols) * (tw + 6f)
            val y = top + (i / cols) * 50f
            if (y + 46f > gy + gh - 40f) break
            val on = id == worn
            val over = Ui.hit("badge:$id", x, y, tw, 46f, { if (it == Keys.MOUSE_LEFT && !on) LunarProfileEdits.equipBadge(id) })
            Ui.box(x, y, tw, 46f, 6f, if (over || on) Tok.FILL2 else Tok.FILL, if (on) 1f else 0.5f, if (on) Tok.alpha(Tok.a1, 0.85f) else Tok.LINE)
            if (id == 0) Ui.icon("x", x + tw / 2f - 7f, y + 7f, 14f, Tok.T3)
            else if (!LunarNameTag.canvasBadge(id, x + tw / 2f - 10f, y + 5f, 20f)) Ui.icon("fav", x + tw / 2f - 7f, y + 7f, 14f, Tok.T4)
            val name = if (id == 0) "None" else LunarBadges.badge(id)?.name ?: "#$id"
            Ui.textC(Type.tiny, Ui.fit(Type.tiny, name, tw - 4f), x + tw / 2f, y + 35f, if (on) Tok.T1 else Tok.T2)
            i++
        }
        // Lunar+ colours.
        val py = gy + gh - 30f
        Ui.box(gx, py - 4f, gw, 0.5f, 0f, Tok.LINE)
        Ui.text(Type.section, "LUNAR+ COLOUR", gx + 2f, py + 3f, Tok.T4)
        val colors = LunarProfileEdits.plusColors
        if (colors.isEmpty()) {
            Ui.text(Type.small, "Lunar+ only (Lunar offers no colours to this account)", gx + 2f, py + 17f, Tok.T3)
            return
        }
        val current = LunarProfileEdits.plusColor() and 0xFFFFFF
        var cx = gx + 2f
        for (c in colors) {
            val on = (c and 0xFFFFFF) == current
            val over = Ui.hit("plus:$c", cx, py + 10f, 16f, 16f, { if (it == Keys.MOUSE_LEFT) LunarProfileEdits.setPlusColor(c) })
            Ui.box(cx, py + 10f, 16f, 16f, 5f, c, if (on) 1.5f else 0.5f, if (on) Tok.WHITE else if (over) Tok.LINE2 else Tok.LINE)
            cx += 20f
        }
    }
}
