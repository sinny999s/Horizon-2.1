package dev.horizon.client.hud.widgets

import dev.horizon.client.config.ItemMacro
import dev.horizon.client.config.Macros
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.hud.HudType
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.render.Canvas

/**
 * A 16-high tile per enabled item macro, the item and its key; the key is grey while held and red when
 * the item isn't in the inventory.
 * The new look (hud.css .mtile): the item and its key in a 16-tall tile, the cooldown shown.
 */
object MacroWidget : HudWidget("macro-widget") {
    /** A tile drawn this frame, for its keycap and cooldown over the plates. */
    private class Tile(val x: Float, val w: Float, val label: String, val color: Int, val cooldown: Float)

    private val tiles = ArrayList<Tile>()

    override fun render() {
        val p = mc.player ?: return
        tiles.clear()
        var x = left
        for (m in Macros.list.filterIsInstance<ItemMacro>().filter { it.enabled }) {
            val item = Macros.item(m.item) ?: continue
            val found = (36 downTo 0).any { p.inventory.getItem(it).`is`(item) }
            val held = if (m.bind.mouse) Keys.heldMouse.contains(m.bind.key) else Keys.down(m.bind.key)
            val label = Keys.name(m.bind)
            val w = 2.5f + 13f + 3f + HudType.letter.width(label) + 7f + 4.5f
            plate(x, top, w, 16f)
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(x + 2.5f, top + 1.5f)
            pose.scale(0.8125f, 0.8125f)
            Canvas.graphics?.item(item.defaultInstance, 0, 0)
            pose.popMatrix()
            val cd = if (p.cooldowns.isOnCooldown(item.defaultInstance))
                p.cooldowns.getCooldownPercent(item.defaultInstance, mc.deltaTracker.getGameTimeDeltaPartialTick(false)) else 0f
            tiles += Tile(x, w, label, if (held) Tok.T4 else if (found) Tok.T2 else 0xFFFF7A7A.toInt(), cd)
            x += w + 1f
        }
        size(if (tiles.isEmpty()) 0f else x - left - 1f, 16f)
    }

    override fun postRender() {
        for (t in tiles) {
            if (t.cooldown > 0f) Ui.box(t.x, top + 16f * (1f - t.cooldown), t.w, 16f * t.cooldown, minOf(3.5f, 8f * t.cooldown), 0x40FF5D5D)
            val kw = HudType.letter.width(t.label) + 7f
            val kx = t.x + 18.5f
            Ui.box(kx, top + 3f, kw, 10f, 2.5f, 0x0FFFFFFF, 0.5f, 0x1AFFFFFF)
            Ui.box(kx + 0.5f, top + 12f, kw - 1f, 0.5f, 0f, 0x1AFFFFFF)
            HudType.letter.drawMiddleCentered(t.label, kx + kw / 2f, top + 7.75f, t.color)
        }
    }
}
