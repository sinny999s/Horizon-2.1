package dev.horizon.client.hud.widgets

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.ScoreBoardRenderEvent
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.HudWidget
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import kotlin.math.max

/**
 * The server's sidebar scoreboard in the HUD's look, movable in the HUD editor. The same lines as
 * vanilla's sidebar (your team colour's objective if it has one, else the sidebar's; up to 15 entries, highest score
 * first, names inside their team's prefix and suffix, scores in the objective's own number format, so a server that
 * hides them keeps them hidden), as a title plate over one panel, with the server's colours. Vanilla's own sidebar is
 * hidden while this shows it ([hideVanilla]); [scores] can drop the numbers too.
 */
object ScoreboardWidget : HudWidget("scoreboard-widget") {
    private val scores = bool("scores", true)
    private val hideVanilla = bool("hide-vanilla", true)

    /** A run of text in one colour. */
    private class Run(val text: String, val color: Int)
    private class Line(val name: List<Run>, val score: List<Run>)

    private val anim = Animation(Easing.OUT_BACK, 250)

    init {
        EventBus.on<ScoreBoardRenderEvent>(this) { e -> if (enabled && hideVanilla.value) e.cancel() }
    }

    override fun render() {
        val objective = sidebar()
        val (title, lines) = if (objective != null) {
            runs(objective.displayName, Tok.T1) to lines(objective)
        } else if (editorMode) {
            SAMPLE_TITLE to SAMPLE
        } else {
            anim.update(0f)
            size(0f, 0f)
            return
        }
        anim.update(1f)
        val a = anim.value.coerceIn(0f, 1f)
        val alignRight = left - screenW / 2f > 0

        // The title plate: the icon, a divider, the objective's name.
        val titleW = width(title) + 25f
        var w = titleW
        for (l in lines) w = max(w, PAD + width(l.name) + (if (l.score.isEmpty()) 0f else GAP + width(l.score)) + PAD)
        val hx = left + if (alignRight) w - titleW else 0f
        plate(hx, top, titleW, 16f, a)
        icon(hx + 3.5f, top + 3f, a)
        divider(hx + 16.5f, top + 3.75f, a)
        draw(title, hx + 20f, top + 8f, a)
        if (lines.isEmpty()) {
            size(w, 16f)
            return
        }

        // One panel under it: names on the left, scores on the right.
        val py = top + 17f
        val ph = lines.size * ROW + 5f
        plate(left, py, w, ph, a)
        var y = py + 2.5f + ROW / 2f
        for (l in lines) {
            draw(l.name, left + PAD, y, a)
            if (l.score.isNotEmpty()) draw(l.score, left + w - PAD - width(l.score), y, a)
            y += ROW
        }
        size(w, 17f + ph)
    }

    /** The objective vanilla's sidebar would show: your team colour's own slot first, then the sidebar's. */
    private fun sidebar(): Objective? {
        val p = mc.player ?: return null
        val board = mc.level?.scoreboard ?: return null
        board.getPlayersTeam(p.scoreboardName)?.color?.let { c ->
            if (c.isPresent) board.getDisplayObjective(c.get().displaySlot())?.let { return it }
        }
        return board.getDisplayObjective(DisplaySlot.SIDEBAR)
    }

    private fun lines(o: Objective): List<Line> {
        val board = o.scoreboard
        val format = o.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)
        return board.listPlayerScores(o).asSequence()
            .filter { !it.isHidden }
            .sortedWith(ORDER)
            .take(15)
            .map { e ->
                val name = PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName())
                Line(runs(name, Tok.T1), if (scores.value) runs(e.formatValue(format), SCORE) else emptyList())
            }
            .toList()
    }

    /**
     * [c] as coloured runs, read exactly as vanilla's sidebar reads it: through vanilla's own formatted-text order, so
     * `§` codes in the text (old-style servers) and their quirks act as they do there, and a character vanilla's font
     * draws as nothing is left out too (servers such as Hypixel add such characters to keep their lines apart).
     */
    private fun runs(c: Component, base: Int): List<Run> {
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var color = base
        Language.getInstance().getVisualOrder(c).accept { _, style, cp ->
            if (cp != ' '.code && !vanillaDraws(cp, style)) return@accept true
            val col = style.color?.let { 0xFF000000.toInt() or it.value } ?: base
            if (col != color && sb.isNotEmpty()) out += Run(sb.toString(), color).also { sb.clear() }
            color = col
            sb.appendCodePoint(cp)
            true
        }
        if (sb.isNotEmpty()) out += Run(sb.toString(), color)
        return out
    }

    private val drawn = HashMap<Pair<Int, Any?>, Boolean>()

    /** Vanilla's `Font.getGlyph(codepoint, style)`: the glyph its text drawing uses. */
    private val getGlyph = Font::class.java.getDeclaredMethod("getGlyph", Int::class.javaPrimitiveType, Style::class.java)
        .apply { isAccessible = true }

    /**
     * Whether vanilla's font draws [cp] in [style]'s font as anything: only a glyph on its texture sheet, with a width,
     * does; an empty glyph, the invisible one it uses for a character it has none for, or one a resource pack or mod
     * made zero-width (Hypixel's lines end in emoji that the player's font may blank), draws nothing.
     */
    private fun vanillaDraws(cp: Int, style: Style): Boolean {
        if (cp < 0x7f) return true
        return drawn.getOrPut(cp to style.font) {
            val font = Style.EMPTY.withFont(style.font)
            val glyph = getGlyph.invoke(mc.font, cp, font)
            val advance = mc.font.width(Component.literal(String(Character.toChars(cp))).withStyle(font))
            glyph is BakedSheetGlyph && advance > 0
        }
    }

    private fun width(runs: List<Run>) = runs.sumOf { tw(it.text).toDouble() }.toFloat()

    private fun draw(runs: List<Run>, x: Float, cy: Float, a: Float) {
        var cx = x
        for (r in runs) {
            ctext(r.text, cx, cy, Colors.withAlpha(r.color, Colors.alphaF(r.color) * a))
            cx += tw(r.text)
        }
    }

    /** Vanilla's sidebar order: highest score first, then by name. */
    private val ORDER = Comparator.comparingInt<PlayerScoreEntry> { it.value() }.reversed()
        .thenComparing({ it.owner() }, String.CASE_INSENSITIVE_ORDER)

    /** Vanilla's sidebar red, for a score with no colour of its own. */
    private const val SCORE = 0xFFFF5555.toInt()
    private const val ROW = 10f
    private const val PAD = 5f
    private const val GAP = 8f

    /** What the HUD editor shows with no scoreboard up, so it can be placed. */
    private val SAMPLE_TITLE = listOf(Run("BED WARS", 0xFFFFFF55.toInt()))
    private val SAMPLE = listOf(
        Line(listOf(Run("Diamond II in 4:12", Tok.T1)), emptyList()),
        Line(listOf(Run("R ", 0xFFFF5555.toInt()), Run("Red: ", Tok.T1), Run("✔", 0xFF55FF55.toInt())), emptyList()),
        Line(listOf(Run("B ", 0xFF5555FF.toInt()), Run("Blue: ", Tok.T1), Run("2", 0xFFFFFF55.toInt())), emptyList()),
        Line(listOf(Run("Kills: ", Tok.T1), Run("3", 0xFF55FF55.toInt())), emptyList()),
    )
}
