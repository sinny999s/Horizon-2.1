package dev.horizon.client.hud.widgets

import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.hud.HudType
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Overlay
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Helmet, chestplate, leggings, boots (inventory slots 39..36). */
private fun armor(): List<ItemStack> {
    val inv = net.minecraft.client.Minecraft.getInstance().player?.inventory ?: return emptyList()
    return listOf(inv.getItem(39), inv.getItem(38), inv.getItem(37), inv.getItem(36))
}

/**
 * The four armour pieces with durability bars.
 * The new look (hud.css .slots): four 20-unit slots, dividers between them.
 */
object ArmorWidget : HudWidget("armor-widget") {
    override fun render() {
        plate(left, top, 85f, 22f)
        for ((i, stack) in armor().withIndex()) {
            val sx = left + 2.5f + i * 20f
            if (i > 0) divider(sx - 0.5f, top + 7f)
            if (stack.isEmpty) continue
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(sx + 2f, top + 0.5f)
            Canvas.graphics?.item(stack, 0, 0)
            pose.popMatrix()
        }
        size(85f, 22f)
    }

    /** .dura: a 16x3 track in the faint theme colour, filled with its gradient (none for an empty or unbreakable slot). */
    override fun postRender() {
        for ((i, stack) in armor().withIndex()) {
            val bx = left + 4.5f + i * 20f
            val by = top + 16.5f
            Ui.box(bx, by, 16f, 3f, 1.5f, Tok.alpha(Tok.a1, 0.25f))
            val f = (1f - stack.damageValue.toFloat() / stack.maxDamage).coerceIn(0f, 1f)
            if (stack.isEmpty || f.isNaN() || f <= 0f) continue
            Ui.glow(bx, by, 16f * f, 3f, 1.5f, 3f, Tok.alpha(Tok.a1, 0.35f))
            Ui.hbox(bx, by, 16f * f, 3f, 1.5f, Tok.a1, Tok.a2)
        }
    }
}

/**
 * Totems, crystals, xp, gapples, crapples, fireworks and obsidian (as enabled) with their counts,
 * "-" for none.
 * The new look (hud.css .res): 20-unit slots, the count under each item.
 */
object PvpResourcesWidget : HudWidget("pvp-resources-widget") {
    private val totems = bool("totems", true)
    private val crystals = bool("crystals", true)
    private val gapples = bool("gapples", true)
    private val crapples = bool("crapples", false)
    private val fireworks = bool("fireworks", true)
    private val xp = bool("xp", true)
    private val obby = bool("obby", false)

    override fun render() {
        val items = buildList {
            if (totems.value) add(Items.TOTEM_OF_UNDYING)
            if (crystals.value) add(Items.END_CRYSTAL)
            if (xp.value) add(Items.EXPERIENCE_BOTTLE)
            if (gapples.value) add(Items.ENCHANTED_GOLDEN_APPLE)
            if (crapples.value) add(Items.GOLDEN_APPLE)
            if (fireworks.value) add(Items.FIREWORK_ROCKET)
            if (obby.value) add(Items.OBSIDIAN)
        }
        val w = 5f + 20f * items.size
        plate(left, top, w, 29f)
        for ((i, item) in items.withIndex()) {
            val sx = left + 2.5f + i * 20f
            if (i > 0) divider(sx - 0.5f, top + 7f)
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(sx + 2f, top + 1f)
            Canvas.graphics?.item(item.defaultInstance, 0, 0)
            pose.popMatrix()
            val n = ServerInfo.count(item)
            val label = if (n <= 0) "-" else n.toString()
            ctext(label, sx + 10f - tw(label, HudType.count) / 2f, top + 23.5f, Tok.T1, HudType.count)
        }
        size(w, 29f)
    }
}

/**
 * "Inventory" and the 27 main-inventory slots at 0.75 scale with their counts. The items are the game's GUI items, so
 * block items keep the game's 3-D lighting. The counts are drawn after the items.
 * The new look (hud.css .panel and .inv): one panel, its header row, 15-unit cells 1 apart.
 */
object InventoryWidget : HudWidget("inventory-widget") {
    override fun render() {
        plate(left, top, 158f, 72f, radius = 4.5f)
        icon(left + 4.5f, top + 5f)
        divider(left + 17.5f, top + 5.75f)
        ctext("Inventory", left + 21f, top + 10f, Tok.T1)
        val inv = mc.player?.inventory
        if (inv != null) for (i in 0 until 27) {
            val stack = inv.getItem(9 + i)
            if (stack.isEmpty) continue
            val pose = Canvas.pose
            pose.pushMatrix()
            pose.translate(left + 7.5f + i % 9 * 16f + 1.5f, top + 18.5f + i / 9 * 16f + 1.5f)
            pose.scale(0.75f, 0.75f)
            Canvas.graphics?.item(stack, 0, 0)
            pose.popMatrix()
        }
        size(158f, 72f)
    }

    /** The cells over the panel, and the counts over the items (with the CSS text-shadow). */
    override fun postRender() {
        for (i in 0 until 27) Ui.box(left + 7.5f + i % 9 * 16f, top + 18.5f + i / 9 * 16f, 15f, 15f, 2.5f, 0x0DFFFFFF)
        val inv = mc.player?.inventory ?: return
        val x0 = left
        val y0 = top
        Overlay.late {
            for (i in 0 until 27) {
                val st = inv.getItem(9 + i)
                if (st.count <= 1) continue
                val s = st.count.toString()
                val right = x0 + 7.5f + i % 9 * 16f + 15f - 0.5f
                val cy = y0 + 18.5f + i / 9 * 16f + 12.5f
                val w = HudType.tiny.width(s)
                HudType.tiny.drawMiddle(s, right - w, cy + 0.5f, 0xCC000000.toInt())
                HudType.tiny.drawMiddle(s, right - w, cy, -1)
            }
        }
    }
}
