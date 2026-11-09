package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.gui.Cursor
import dev.horizon.client.mixin.FaviconTextureAccessor
import dev.horizon.client.mixin.SelectWorldScreenAccessor
import dev.horizon.client.mixin.WorldListEntryAccessor
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.Theme
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The Singleplayer screen in the client's style ([ListMenu] over vanilla's): each world as a pane with
 * its icon, name, folder, when it was last played and vanilla's info line (mode, cheats, version, or why it can't be
 * opened), and a search field (vanilla's search box, which typing goes to).
 */
class WorldsScreen(vanilla: SelectWorldScreen) : ListMenu<WorldSelectionList.Entry>(vanilla, "Singleplayer") {
    private val list get() = (vanilla as SelectWorldScreenAccessor).horizon_list()
    private val search get() = (vanilla as SelectWorldScreenAccessor).horizon_searchBox()

    // The search field in the last frame.
    private var searchX = 0f
    private var searchY = 0f

    override fun entries(): List<WorldSelectionList.Entry> = list.children()

    override fun selected(): WorldSelectionList.Entry? = list.selected

    override fun select(entry: WorldSelectionList.Entry) = list.setSelected(entry)

    override fun selectable(entry: WorldSelectionList.Entry) = entry is WorldSelectionList.WorldListEntry

    override fun rowHeight(entry: WorldSelectionList.Entry) = if (entry is WorldSelectionList.WorldListEntry) 42f else 30f

    override fun open(entry: WorldSelectionList.Entry) {
        (entry as? WorldSelectionList.WorldListEntry)?.joinWorld()
    }

    // Vanilla's list takes in the worlds it loaded in the background when it's drawn.
    override fun beforeRows() = list.extractWidgetRenderState(scratchGraphics(), -1, -1, 0f)

    override fun subtitle(): String {
        val n = list.children().count { it is WorldSelectionList.WorldListEntry }
        return if (n == 1) "1 world" else "$n worlds"
    }

    override fun drawHeaderExtra(right: Float, y: Float, mouseX: Float, mouseY: Float) {
        val box = search
        searchX = right - SEARCH_W
        searchY = y
        val hovered = mouseX >= searchX && mouseX <= right && mouseY >= y && mouseY <= y + 16f
        if (hovered) Cursor.request(CursorTypes.IBEAM)
        Menus.glass(searchX, y, SEARCH_W, 16f, 5f, 0xFF3A3A3A.toInt(), if (box.isFocused) 0x60FFFFFF else 0x30FFFFFF)
        val font = Fonts.medium75
        val text = box.value
        val tx = searchX + 7f
        if (text.isEmpty()) {
            font.drawMiddle("Search worlds", tx, y + 8f, 0x80FFFFFF.toInt())
        } else {
            font.drawMiddle(Menus.fit(text, font, SEARCH_W - 16f), tx, y + 8f, Theme.text)
        }
        if (box.isFocused && System.currentTimeMillis() / 500 % 2 == 0L) {
            val cx = tx + minOf(font.width(text), SEARCH_W - 16f) + 0.5f
            Menus.rect(cx, y + 4f, 1f, 8f, 0f, 0xC0FFFFFF.toInt())
        }
    }

    override fun headerExtraClicked(x: Float, y: Float): Boolean {
        if (x < searchX || x > searchX + SEARCH_W || y < searchY || y > searchY + 16f) return false
        vanilla.focused = search
        return true
    }

    override fun drawRow(entry: WorldSelectionList.Entry, x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean) {
        if (entry !is WorldSelectionList.WorldListEntry) {
            // Vanilla's loading row, or the one saying there are no worlds.
            val text = if (entry.javaClass.simpleName.contains("Loading")) "Loading worlds" + Menus.dots() else "No worlds yet"
            Fonts.medium75.drawMiddleCentered(text, x + w / 2f, y + h / 2f, Menus.GREY)
            return
        }
        Menus.card(x, y, w, h, hovered, selected)
        val summary = entry.levelSummary
        val icon = ((entry as Any as WorldListEntryAccessor).horizon_icon() as FaviconTextureAccessor).horizon_texture()
        if (icon != null) {
            Menus.image(icon.textureView, x + 5f, y + 5f, 32f, 32f, 5f)
        } else {
            val a = 0x8C5A5A62.toInt()
            val b = 0x8C2A2A30.toInt()
            Menus.rect(x + 5f, y + 5f, 32f, 32f, 5f, a, b, b, a)
            Fonts.bold12.drawMiddleCentered(summary.levelName.take(1).uppercase(), x + 21f, y + 21f, -1)
        }
        val tx = x + 45f
        val tw = w - 52f
        Fonts.medium95.draw(Menus.fit(summary.levelName, Fonts.medium95, tw), tx, y + 5f, Theme.text)
        Fonts.medium75.draw(Menus.fit("${summary.levelId}  -  ${played(summary.lastPlayed)}", Fonts.medium75, tw), tx, y + 17f, Menus.GREY)
        val info = summary.info.string
        val warn = summary.isLocked || summary.requiresManualConversion() || !summary.isCompatible
        Fonts.medium75.draw(Menus.fit(info, Fonts.medium75, tw), tx, y + 27f, if (warn) 0xFFF87171.toInt() else Menus.GREY)
    }

    /** When a world was last played, in words. */
    private fun played(ms: Long): String {
        if (ms <= 0L) return "never played"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "played just now"
            s < 3600 -> "played ${s / 60} min ago"
            s < 86400 -> "played ${s / 3600} h ago"
            s < 2 * 86400 -> "played yesterday"
            s < 30 * 86400 -> "played ${s / 86400} days ago"
            else -> "played " + SimpleDateFormat("d MMM yyyy").format(Date(ms))
        }
    }

    private companion object {
        const val SEARCH_W = 130f
    }
}
