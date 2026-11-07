package dev.horizon.client.module.cosmetic

import dev.horizon.client.HorizonClient
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Easing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GlyphSource
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.glyphs.EffectGlyph
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.util.StringDecomposer
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2fc
import org.joml.Vector2f
import java.util.IdentityHashMap

/**
 * Not in the original: chat in the client's style. Its text is set in Inter SemiBold (`font/chat.json`, hinted for
 * screens; vanilla's glyphs for anything Inter lacks), bold parts in Inter ExtraBold, through vanilla's own font system, as a
 * font on each message's style, so vanilla still wraps, draws and hit-tests the lines (links, hovers and clicks stay exact). The input bar's
 * text and the command suggestions use it too. Vanilla's black strip behind each line becomes one rounded dark pane
 * behind all the visible lines, fading as they fade; the input bar gets a rounded pane of its own. With chat closed, a
 * new message slides the lines up into place.
 *
 * How: with no screen open, vanilla's chat (drawn under the client's 2D layer) is held back and drawn again from that
 * layer ([renderLayer]), after the pane, so the text sits on it. With a screen open the client's layer goes first, so
 * the pane is drawn there (from the last frame's lines) and vanilla's chat lands on it as usual.
 */
object BetterChat : Module("better-chat", Categories.COSMETIC, listOf("chat")) {
    val clientFont = bool("client-font", true)
    val pane = bool("pane", true)
    val opacity = int("opacity", 90, 0..100) { pane.value }
    val slide = bool("slide", true)

    private val CHAT = FontDescription.Resource(Identifier.fromNamespaceAndPath(HorizonClient.ID, "chat"))

    /** Inter ExtraBold (`font/chat_bold.json`), for bold parts in place of vanilla's fake bold (each glyph drawn twice, a unit apart). */
    private val CHAT_BOLD = FontDescription.Resource(Identifier.fromNamespaceAndPath(HorizonClient.ID, "chat_bold"))

    private var fontWas = false
    private var paneWas = false

    init {
        on<dev.horizon.client.event.TickEvent> {
            // Toggling the font or the pane re-wraps the lines (vanilla wraps them once, as they come in).
            if (clientFont.value != fontWas || pane.value != paneWas) refresh()
        }
    }

    override fun onEnable() = refresh()

    override fun onDisable() = refresh()

    private fun refresh() {
        fontWas = clientFont.value
        paneWas = pane.value
        Minecraft.getInstance().gui.hud.chat.rescaleChat()
    }

    private val fontOn get() = enabled && clientFont.value

    /** A message's text set in the client's font (GuiMessageMixin, as vanilla wraps it); parts with a font of their own keep it. */
    @JvmStatic
    fun restyle(content: FormattedText): FormattedText {
        if (!fontOn || content !is Component) return content
        // On the pane, no shadow: vanilla's hard black one doubles a smooth font's letters (parts with their own keep it).
        return Component.empty().append(realBold(content)).withStyle { if (pane.value) it.withFont(CHAT).withoutShadow() else it.withFont(CHAT) }
    }

    /**
     * [c] with its bold parts (and what they hold) set in Inter ExtraBold instead of bolded by vanilla, whether bold by
     * style or by a `§l` in the text itself (servers like Hypixel send `§e§l[Teleport to the Hotel]`): vanilla's fake bold
     * draws each glyph twice a unit apart, which fills in a smooth font's e's and o's. Parts with a font of their own keep it;
     * a translation's arguments get the same.
     */
    private fun realBold(c: Component): Component {
        val contents = c.contents
        val text = (contents as? PlainTextContents)?.text()
        val out = when {
            text != null && text.indexOf('§') >= 0 -> MutableComponent.create(PlainTextContents.EMPTY).also { legacy(text, it) }
            contents is TranslatableContents && contents.args.isNotEmpty() -> MutableComponent.create(TranslatableContents(contents.key, contents.fallback,
                Array(contents.args.size) { i -> contents.args[i].let { a -> if (a is Component) realBold(a) else if (a is String && a.indexOf('§') >= 0) realBold(Component.literal(a)) else a } }))
            else -> MutableComponent.create(contents)
        }
        out.style = boldFont(c.style)
        for (sibling in c.siblings) out.append(realBold(sibling))
        return out
    }

    private fun boldFont(style: Style): Style =
        if (style.isBold && style.font == FontDescription.DEFAULT) style.withFont(CHAT_BOLD).withBold(false) else style

    /** [text]'s `§` codes turned into styled parts appended to [into] (as vanilla would apply them while drawing). */
    private fun legacy(text: String, into: MutableComponent) {
        val run = StringBuilder()
        var runStyle: Style? = null
        StringDecomposer.iterateFormatted(text, Style.EMPTY) { _, style, codePoint ->
            if (style != runStyle && run.isNotEmpty()) {
                into.append(Component.literal(run.toString()).setStyle(boldFont(runStyle!!)))
                run.setLength(0)
            }
            runStyle = style
            run.appendCodePoint(codePoint)
            true
        }
        if (run.isNotEmpty()) into.append(Component.literal(run.toString()).setStyle(boldFont(runStyle!!)))
    }

    private val fonts = IdentityHashMap<Font, Font>()

    /** [base] with the client's font in place of vanilla's default one: for the input bar and its suggestions (ChatScreenMixin). */
    @JvmStatic
    fun inputFont(base: Font, provider: Font.Provider): Font {
        if (!fontOn) return base
        return fonts.getOrPut(base) {
            Font(object : Font.Provider {
                override fun glyphs(description: FontDescription): GlyphSource =
                    provider.glyphs(if (description == FontDescription.DEFAULT && fontOn) CHAT else description)

                override fun effect(): EffectGlyph = provider.effect()
            })
        }
    }

    // ---- the pane behind the lines ----

    /** Vanilla's chat call held back this frame (no screen open), to draw from the client's layer. */
    private var held: ((GuiGraphicsExtractor) -> Unit)? = null

    /** Drawing that held call now (ChatComponentMixin lets it through). */
    @JvmStatic
    var replaying = false
        private set

    /** Whether vanilla's chat call should wait for the client's layer: chat closed, no screen. */
    @JvmStatic
    fun hold(draw: (GuiGraphicsExtractor) -> Unit): Boolean {
        if (!enabled || replaying || Minecraft.getInstance().gui.screen() != null) return false
        held = draw
        return true
    }

    // The lines' strips seen during the current chat draw, in GUI units, and the last whole draw's.
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var alpha = 0f
    private var counting = false

    /** A whole chat draw ended since the client's layer last drew the pane (none: chat isn't showing, F1 or hidden). */
    private var seen = false
    private val last = FloatArray(5)
    private val v = Vector2f()

    @JvmStatic
    fun beginLines() {
        counting = true
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        alpha = 0f
    }

    @JvmStatic
    fun endLines() {
        if (!counting) return
        counting = false
        last[0] = minX
        last[1] = minY
        last[2] = maxX
        last[3] = maxY
        last[4] = alpha
        seen = true
    }

    /**
     * A strip vanilla fills behind a line ([color] black, alpha by its fade and the text background option): taken for the
     * pane instead of drawn when the pane is on (ChatFillMixin). Other fills (the scroll bar, a message's tag) are drawn.
     */
    @JvmStatic
    fun lineFill(pose: Matrix3x2fc, x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Boolean {
        if (!enabled || !pane.value || color and 0xFFFFFF != 0) return false
        pose.transformPosition(x0.toFloat(), y0.toFloat(), v)
        minX = minOf(minX, v.x)
        minY = minOf(minY, v.y)
        pose.transformPosition(x1.toFloat(), y1.toFloat(), v)
        maxX = maxOf(maxX, v.x)
        maxY = maxOf(maxY, v.y)
        val bg = Minecraft.getInstance().options.textBackgroundOpacity().get().toFloat().coerceAtLeast(0.01f)
        alpha = maxOf(alpha, ((color ushr 24) / 255f / bg).coerceIn(0f, 1f))
        return true
    }

    @JvmStatic
    fun hidesTags() = enabled && pane.value

    // The input bar's rect (ChatScreenMixin), taken instead of vanilla's black fill.
    private val input = FloatArray(4)
    private var inputFrame = false

    @JvmStatic
    fun inputFill(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        if (!enabled || !pane.value) return false
        input[0] = x0.toFloat()
        input[1] = y0.toFloat()
        input[2] = x1.toFloat()
        input[3] = y1.toFloat()
        inputFrame = true
        return true
    }

    // When the newest message came in (for the slide), by the identity of the newest message.
    private var newest: Any? = null
    private var arrived = 0L

    /** From the client's world layer (Overlay): the pane, then the held chat over it. */
    @JvmStatic
    fun renderLayer(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        val call = held
        held = null
        if (!enabled) return
        val screen = mc.gui.screen()
        if (call != null) {
            // The slide: the lines start a line lower and ease up into place.
            val top = (mc.gui.hud.chat as dev.horizon.client.mixin.ChatComponentAccessor).horizon_allMessages().firstOrNull()
            val now = System.currentTimeMillis()
            if (top !== newest) {
                if (newest != null) arrived = now
                newest = top
            }
            val t = ((now - arrived) / 220f).coerceIn(0f, 1f)
            val dy = if (slide.value && t < 1f) (1f - Easing.OUT_CUBIC.apply(t.toDouble()).toFloat()) * 9f * mc.options.chatScale().get().toFloat() else 0f
            graphics.pose().pushMatrix()
            graphics.pose().translate(0f, dy)
            replaying = true
            try {
                call(graphics)
            } finally {
                replaying = false
                graphics.pose().popMatrix()
            }
        }
        if (pane.value) {
            // A little room above the top line and below the bottom one (vanilla's strips end at the text).
            if (seen && last[4] > 0.01f && last[2] > last[0]) linesPane(last[0], last[1] - 1f, last[2] - last[0], last[3] - last[1] + 3f, last[4])
            if (screen is ChatScreen && inputFrame) inputPane(input[0], input[1], input[2] - input[0], input[3] - input[1])
        }
        inputFrame = false
        seen = false
    }

    private fun linesPane(x: Float, y: Float, w: Float, h: Float, a: Float) {
        val was = Canvas.alpha
        Canvas.alpha = a * opacity.value / 100f
        Ui.glow(x, y + 3f, w, h, 6f, 12f, 0x40000000)
        Ui.vbox(x, y, w, h, 6f, 0xD914141A.toInt(), 0xF20E0E12.toInt(), 0.5f, 0x1AFFFFFF)
        Canvas.alpha = was
    }

    private fun inputPane(x: Float, y: Float, w: Float, h: Float) {
        val was = Canvas.alpha
        Canvas.alpha = maxOf(0.35f, opacity.value / 100f)
        Ui.vbox(x, y, w, h, 4f, 0xE618181E.toInt(), 0xF2121216.toInt(), 0.5f, 0x2EFFFFFF)
        Canvas.alpha = was
    }
}
