package dev.horizon.client.gui.modern

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Keys
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.VectorIcon
import dev.horizon.client.render.WebText
import dev.horizon.client.render.Theme
import net.minecraft.client.Minecraft
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.min

/**
 * The new ClickGUI's look (design/clickgui/index.html), in GUI units. The mockup is a 1600x900 window
 * at GUI scale 2, so its pixels halved are these units; its colours are below, with the theme's primary and secondary
 * standing in for its two accents.
 */
internal object Tok {
    val T1 = 0xFFF5F5F7.toInt()
    val T2 = 0xA3EBEBF5.toInt()
    val T3 = 0x61EBEBF5
    val T4 = 0x38EBEBF5
    val LINE = 0x12FFFFFF
    val LINE2 = 0x1CFFFFFF
    val FILL = 0x0AFFFFFF
    val FILL2 = 0x12FFFFFF
    val WHITE = -1

    val a1: Int get() = Theme.primary or 0xFF000000.toInt()
    val a2: Int get() = Theme.secondary or 0xFF000000.toInt()

    /** CSS's color-mix(in oklab, [c] t, transparent), close enough in sRGB. */
    fun alpha(c: Int, t: Float) = Colors.withAlpha(c, t)

    /** color-mix([c] (1 - t), white t). */
    fun light(c: Int, t: Float) = Colors.lerp(c or 0xFF000000.toInt(), -1, t)

    /** The accent half-way between primary and secondary, for one-colour fills of the mockup's gradients. */
    val mid: Int get() = Colors.lerp(a1, a2, 0.5f)
}

/** The mockup's type scale: CSS px / 2, SF Pro as in the game's fonts, with the mockup's letter-spacing (CSS px). */
internal object Type {
    val label by lazy { WebText("sf_medium", 6.5f) }
    val nav by lazy { WebText("sf_medium", 6.75f) }
    val small by lazy { WebText("sf_medium", 6f) }
    val tiny by lazy { WebText("sf_medium", 5.5f) }
    val sub by lazy { WebText("sf_medium", 6.25f) }
    val status by lazy { WebText("sf_medium", 5.75f) }
    val chip by lazy { WebText("sf_semibold", 5.25f) }
    val ver by lazy { WebText("sf_semibold", 5f) }
    val crumb by lazy { WebText("sf_semibold", 5.25f, 0.8f) }
    val section by lazy { WebText("sf_bold", 5f, 1.1f) }
    val semi by lazy { WebText("sf_semibold", 6.5f) }
    val value by lazy { WebText("sf_semibold", 6.25f) }
    val statusB by lazy { WebText("sf_semibold", 5.75f) }
    val name by lazy { WebText("sf_semibold", 7.25f, -0.1f) }
    val desc by lazy { WebText("sf_regular", 6f) }
    val body by lazy { WebText("sf_regular", 6.25f) }
    val tip by lazy { WebText("sf_bold", 5.75f) }
    val brand by lazy { WebText("sf_bold", 8.5f, -0.2f) }
    val h2 by lazy { WebText("sf_bold", 10.5f, -0.3f) }
    val title by lazy { WebText("sf_bold", 12.5f, -0.4f) }
    val wtitle by lazy { WebText("sf_semibold", 7f, -0.1f) }
    val flabel by lazy { WebText("sf_semibold", 5.25f, 0.9f) }
    val semiS by lazy { WebText("sf_semibold", 6f) }
}

/**
 * The toolkit the new ClickGUI is drawn with: an immediate-mode GUI. Every frame each control is drawn and registers
 * a hit box; input goes to the top-most box under the mouse in the last frame drawn (popovers are drawn last, so they
 * are on top). Hover is read from that same frame, so it never flickers between layers.
 */
internal object Ui {
    /** The mouse in window units (ModernGui maps it into the scaled window). */
    var mx = 0f
    var my = 0f

    class Hit(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val click: ((Int) -> Unit)?,
              val scroll: ((Double) -> Boolean)?, val drag: ((Float, Float) -> Unit)?, val release: (() -> Unit)?) {
        fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    private var hits = ArrayList<Hit>()
    private var hot: String? = null

    /** The drag in progress (a slider, the colour square, a scrollbar) and what ends it. */
    private var dragging: ((Float, Float) -> Unit)? = null
    private var onRelease: (() -> Unit)? = null

    private val clips = ArrayDeque<FloatArray>()

    fun beginFrame() {
        hot = hits.lastOrNull { it.contains(mx, my) }?.id
        hits = ArrayList()
        clips.clear()
    }

    fun hot(id: String) = hot == id

    /** The mouse was on any box in the last frame drawn. */
    val overAny get() = hot != null

    /** A box of the last frame drawn is at ([x], [y]) (between frames: the frame on screen). */
    fun under(x: Float, y: Float) = hits.any { it.contains(x, y) }

    /**
     * Registers [id]'s box (clipped to the current scroll area) and returns whether the mouse is over it. A [drag]
     * handler gets the mouse every frame from the press until the release.
     */
    fun hit(id: String, x: Float, y: Float, w: Float, h: Float, click: ((Int) -> Unit)? = null, scroll: ((Double) -> Boolean)? = null,
            drag: ((Float, Float) -> Unit)? = null, release: (() -> Unit)? = null, pointer: Boolean = true): Boolean {
        var x1 = x
        var y1 = y
        var x2 = x + w
        var y2 = y + h
        clips.lastOrNull()?.let {
            x1 = max(x1, it[0]); y1 = max(y1, it[1]); x2 = min(x2, it[2]); y2 = min(y2, it[3])
        }
        if (x2 <= x1 || y2 <= y1) return false
        hits += Hit(id, x1, y1, x2 - x1, y2 - y1, click, scroll, drag, release)
        val over = hot == id
        if (over && pointer && (click != null || drag != null)) Cursor.request(CursorTypes.POINTING_HAND)
        return over
    }

    /** A blocker: the area takes the mouse (so what's under it doesn't), nothing else. */
    fun block(id: String, x: Float, y: Float, w: Float, h: Float) = hit(id, x, y, w, h, click = {}, scroll = { true }, pointer = false)

    fun pushClip(x: Float, y: Float, w: Float, h: Float) {
        var r = floatArrayOf(x, y, x + w, y + h)
        clips.lastOrNull()?.let { r = floatArrayOf(max(r[0], it[0]), max(r[1], it[1]), min(r[2], it[2]), min(r[3], it[3])) }
        clips.addLast(r)
        Canvas.pushScissor(r[0], r[1], max(r[0], r[2]), max(r[1], r[3]))
    }

    fun popClip() {
        clips.removeLastOrNull()
        Canvas.popScissor()
    }

    /** A press: the top-most box under the mouse gets it. Returns its id, or null when nothing was there. */
    fun press(button: Int): String? {
        val h = hits.lastOrNull { it.contains(mx, my) } ?: return null
        if (h.drag != null && button == Keys.MOUSE_LEFT) {
            dragging = h.drag
            onRelease = h.release
            h.drag.invoke(mx, my)
        }
        h.click?.invoke(button)
        return h.id
    }

    /** The hit id a text field's box registers under, so a click on it keeps it focused. */
    fun fieldId(f: Field) = "field:" + System.identityHashCode(f)

    fun drag() {
        dragging?.invoke(mx, my)
    }

    fun release() {
        dragging = null
        onRelease?.invoke()
        onRelease = null
    }

    val isDragging get() = dragging != null

    /** The wheel: the top-most box under the mouse that takes it. */
    fun scroll(amount: Double): Boolean {
        for (h in hits.asReversed()) if (h.contains(mx, my) && h.scroll != null && h.scroll.invoke(amount)) return true
        return false
    }

    // ---- animation, by id ----

    private val anims = HashMap<String, Animation>()

    /** [id]'s value moving towards [target] (starting there the first time it is asked for). */
    fun anim(id: String, target: Float, ms: Long = 450, easing: Easing = Easing.OUT_EXPO): Float {
        val a = anims.getOrPut(id + "/" + ms) { Animation(easing, ms).also { it.value = target } }
        a.update(target)
        return a.value
    }

    /** Restarts [id] (asked for with [ms]) from [from]: an entrance, also the first time. */
    fun animFrom(id: String, ms: Long, from: Float, easing: Easing = Easing.OUT_EXPO) {
        val a = anims.getOrPut("$id/$ms") { Animation(easing, ms) }
        a.value = from
        a.reset(from)
    }

    // ---- drawing ----

    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, bw: Float = 0f, outline: Int = 0) =
        Canvas.box(x, y, w, h, r, color, bw, outline)

    /** A box in a left-to-right gradient (CSS linear-gradient(90deg, ...)). */
    fun hbox(x: Float, y: Float, w: Float, h: Float, r: Float, left: Int, right: Int, bw: Float = 0f, outline: Int = 0) =
        Canvas.box(x, y, w, h, r, left, right, right, left, bw, outline)

    /** A box in a top-to-bottom gradient (CSS linear-gradient(180deg, ...)). */
    fun vbox(x: Float, y: Float, w: Float, h: Float, r: Float, top: Int, bottom: Int, bw: Float = 0f, outline: Int = 0) =
        Canvas.box(x, y, w, h, r, top, top, bottom, bottom, bw, outline)

    /** A box in a top-left to bottom-right gradient (CSS linear-gradient(135deg, ...)). */
    fun dbox(x: Float, y: Float, w: Float, h: Float, r: Float, from: Int, to: Int, bw: Float = 0f, outline: Int = 0) {
        val mid = Colors.lerp(from, to, 0.5f)
        Canvas.box(x, y, w, h, r, from, mid, to, mid, bw, outline)
    }

    /** A CSS box-shadow glow, fading out by [spread] around a [w] x [h] box, a bit over half of [color] in the box. */
    fun glow(x: Float, y: Float, w: Float, h: Float, r: Float, spread: Float, color: Int, dy: Float = 0f) =
        Canvas.glow(x, y + dy, w, h, r, spread * 0.35f, spread, Colors.withAlpha(color, Colors.alphaF(color) * 0.55f))

    /**
     * The mockup's ambient light: two big blurred glows in the accents behind the windows (#ambient, 50% over the
     * world), drifting slowly, placed on the mockup's 800x450 stage whose origin is at ([ox], [oy]). Drawn over the
     * world and under a window's tint, so the glass picks them up as the browser's backdrop-filter does.
     */
    fun ambient(ox: Float, oy: Float, mask: FloatArray? = null) {
        val t = System.currentTimeMillis() / 1000.0
        fun drift(period: Double) = ((1 - kotlin.math.cos(t / period * Math.PI)) / 2).toFloat()
        val d1 = drift(18.0)
        val d2 = drift(22.0)
        blob(ox + 215f + 70f * d1, oy + 175f + 45f * d1, 155f * (1f + 0.15f * d1), Tok.alpha(Tok.a1, 0.275f), mask)
        blob(ox + 595f - 80f * d2, oy + 325f - 35f * d2, 155f * (1f - 0.1f * d2), Tok.alpha(Tok.a2, 0.225f), mask)
    }

    /** A disc of radius [r] blurred by about a third of it (CSS blur(90px) on a 620px circle), [color] at the middle. */
    private fun blob(cx: Float, cy: Float, r: Float, color: Int, mask: FloatArray?) {
        val core = r * 0.7f
        Canvas.glow(cx - core, cy - core, 2f * core, 2f * core, core, 0f, r * 0.6f, color, mask)
    }

    /** A horizontal hairline fading in from the left and out to the right (CSS transparent, [c], transparent). */
    fun fadeLine(x: Float, y: Float, w: Float, h: Float, c: Int) {
        val clear = c and 0xFFFFFF
        Canvas.quad(x, y, w / 2f, h, clear, c, c, clear)
        Canvas.quad(x + w / 2f, y, w / 2f, h, c, clear, clear, c)
    }

    /** The blurred world under a rounded box; a tint box goes on top. */
    fun blur(x: Float, y: Float, w: Float, h: Float, r: Float) = Canvas.blurBox(x, y, w, h, r)

    /**
     * CSS box-shadow: inset 0 1px 0 on a box with a 1px border: a line under the top border that follows the corners
     * round, thinning out as they turn down.
     */
    fun insetTop(x: Float, y: Float, w: Float, r: Float, color: Int) {
        val inner = r - 0.5f
        Canvas.quad(x + r, y + 0.5f, w - 2f * r, 0.5f, color)
        var d = inner
        while (d > 0f) {
            val top = y + r - sqrt(inner * inner - d * d)
            Canvas.quad(x + r - d, top, 0.25f, 0.5f, color)
            Canvas.quad(x + w - r + d - 0.25f, top, 0.25f, 0.5f, color)
            d -= 0.25f
        }
    }

    fun text(font: WebText, s: String, x: Float, cy: Float, color: Int) = font.drawMiddle(s, x, cy, color)
    fun textC(font: WebText, s: String, cx: Float, cy: Float, color: Int) = font.drawMiddleCentered(s, cx, cy, color)
    fun textR(font: WebText, s: String, right: Float, cy: Float, color: Int) = font.drawMiddle(s, right - font.width(s), cy, color)

    /** [s] cut to [w] with an ellipsis. */
    fun fit(font: WebText, s: String, w: Float): String {
        if (font.width(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && font.width("$t...") > w) t = t.dropLast(1)
        return t.trimEnd() + "..."
    }

    /** [s] wrapped to [w], at most [lines] lines, the last cut with an ellipsis. */
    fun wrap(font: WebText, s: String, w: Float, lines: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        val words = s.split(' ')
        for ((i, word) in words.withIndex()) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (font.width(next) <= w || line.isEmpty()) {
                line = next
                continue
            }
            out += line
            line = word
            if (out.size == lines) {
                out[lines - 1] = fit(font, out[lines - 1] + " " + words.drop(i).joinToString(" "), w)
                return out
            }
        }
        if (line.isNotEmpty()) out += line
        if (out.size > lines) return out.take(lines)
        return out
    }

    private val icons = HashMap<String, VectorIcon>()

    /** One of assets/horizon/vector/gui/<name>.svg (white strokes), [size] units square, tinted [color]. */
    fun icon(name: String, x: Float, y: Float, size: Float, color: Int) {
        icons.getOrPut("$name@$size") { VectorIcon("gui/$name", size) }.draw(x, y, color)
    }

    /** The logo, in the theme's two colours. */
    fun mark(x: Float, y: Float, size: Float) {
        icons.getOrPut("logo@$size") { VectorIcon("gui/logo", size) }.draw(x, y, Tok.a1, Tok.a2)
    }

    // ---- controls ----

    /** The mockup's switch (17x10, or 22x13 big): the knob springs over, the track fills with the accent. */
    fun switch(id: String, x: Float, y: Float, on: Boolean, big: Boolean = false, click: (() -> Unit)? = null): Boolean {
        val w = if (big) 22f else 17f
        val h = if (big) 13f else 10f
        val t = anim("sw:$id", if (on) 1f else 0f, 450, Easing.OUT_BACK)
        val over = if (click != null) hit("sw:$id", x, y, w, h, { if (it == Keys.MOUSE_LEFT) click() }) else false
        val f = t.coerceIn(0f, 1f)
        if (f > 0.01f) glow(x, y, w, h, h / 2f, 5.5f, Tok.alpha(Tok.a1, 0.32f * f))
        hbox(x, y, w, h, h / 2f, Colors.lerp(0x1FFFFFFF, Tok.a1, f), Colors.lerp(0x1FFFFFFF, Tok.a2, f))
        val k = h - 2f
        val kx = x + 1f + (w - h) * t
        glow(kx, y + 1f, k, k, k / 2f, 1.5f, 0x40000000, 1f)
        box(kx, y + 1f, k, k, k / 2f, if (over) 0xFFF4F4F6.toInt() else Tok.WHITE)
        return over
    }

    /** A keycap ([text] in it, 9 tall); returns its width. */
    fun keycap(x: Float, y: Float, text: String, none: Boolean = false, h: Float = 9f): Float {
        val w = Type.chip.width(text) + 6f
        box(x, y, w, h, 2.5f, if (none) 0 else 0x0FFFFFFF, 0.5f, if (none) 0x26FFFFFF else 0x1AFFFFFF)
        if (!none) box(x + 0.5f, y + h - 1f, w - 1f, 0.5f, 0f, 0x1AFFFFFF)
        textC(Type.chip, text, x + w / 2f, y + (h - 0.5f) / 2f, if (none) Tok.T4 else Tok.T2)
        return w
    }

    /**
     * The mockup's segmented control: [labels] in equal parts ([natural]: each its text plus 6 a side, as the filter),
     * the selected one under a thumb that slides between them (accent, or [plain] grey).
     */
    fun seg(id: String, x: Float, y: Float, w: Float, h: Float, labels: List<String>, selected: Int, plain: Boolean = false,
            font: WebText = Type.small, natural: Boolean = false, pick: (Int) -> Unit) {
        box(x, y, w, h, 5f, 0x40000000, 0.5f, Tok.LINE)
        val n = labels.size
        val widths = if (natural) labels.map { font.width(it) + 12f } else List(n) { (w - 3f) / n }
        val lefts = widths.runningFold(x + 1.5f) { a, b -> a + b }
        val tx = anim("seg:$id", selected.toFloat(), 450)
        val i0 = tx.toInt().coerceIn(0, n - 1)
        val i1 = (i0 + 1).coerceAtMost(n - 1)
        val f = tx - i0
        val thx = lefts[i0] + (lefts[i1] - lefts[i0]) * f
        val thw = widths[i0] + (widths[i1] - widths[i0]) * f
        if (plain) box(thx, y + 1.5f, thw, h - 3f, 4f, 0x1AFFFFFF, 0.5f, 0x14FFFFFF)
        else {
            glow(thx, y + 2.5f, thw, h - 3f, 4f, 3f, Tok.alpha(Tok.a1, 0.3f))
            dbox(thx, y + 1.5f, thw, h - 3f, 4f, Tok.a1, Tok.a2)
        }
        for ((i, l) in labels.withIndex()) {
            val bx = lefts[i]
            val bw = widths[i]
            val over = hit("seg:$id:$i", bx, y, bw, h, { if (it == Keys.MOUSE_LEFT && i != selected) pick(i) })
            val c = when {
                i == selected -> Tok.WHITE
                over -> Tok.T2
                else -> Tok.T3
            }
            textC(font, fit(font, l, bw - 4f), bx + bw / 2f, y + h / 2f, c)
        }
    }

    /** The width [seg] takes with natural parts. */
    fun segWidth(labels: List<String>, font: WebText = Type.small) = labels.sumOf { (font.width(it) + 12f).toDouble() }.toFloat() + 3f

    /** A small bordered button with an optional icon; returns its width. */
    fun button(id: String, x: Float, y: Float, h: Float, label: String?, iconName: String?, accent: Boolean = false,
               active: Boolean = false, click: () -> Unit): Float {
        val font = Type.small
        val w = (if (label != null) font.width(label) else 0f) + (if (iconName != null) 7f else 0f) +
            (if (label != null && iconName != null) 3f else 0f) + 9f
        val bw = if (label == null) h else w
        val over = hit("btn:$id", x, y, bw, h, { if (it == Keys.MOUSE_LEFT) click() })
        when {
            accent -> box(x, y, bw, h, 4.5f, if (over) Tok.light(Tok.mid, 0.08f) else Tok.mid)
            active -> box(x, y, bw, h, 4.5f, Tok.FILL2, 0.5f, Tok.alpha(Tok.a1, 0.7f))
            else -> box(x, y, bw, h, 4.5f, if (over) Tok.FILL2 else Tok.FILL, 0.5f, if (over) Tok.LINE2 else Tok.LINE)
        }
        val c = if (accent || over || active) Tok.T1 else Tok.T2
        if (label == null) {
            if (iconName != null) icon(iconName, x + (bw - 7f) / 2f, y + (h - 7f) / 2f, 7f, c)
        } else {
            var tx = x + 4.5f
            if (iconName != null) {
                icon(iconName, tx, y + (h - 7f) / 2f, 7f, c)
                tx += 10f
            }
            text(font, label, tx, y + h / 2f, c)
        }
        return bw
    }

    /** The text field that takes typing (one at a time). */
    var focused: Field? = null

    /** A one-line text field's state; [onChange] runs on every edit. */
    class Field(var text: String = "", val max: Int = 64, val onChange: (String) -> Unit = {}) {
        fun type(c: Char) {
            if (text.length >= max || c.code < 32) return
            text += c
            onChange(text)
        }

        fun key(key: Int): Boolean {
            when (key) {
                Keys.BACKSPACE -> if (text.isNotEmpty()) {
                    text = if (Keys.down(Keys.LCONTROL)) text.trimEnd().substringBeforeLast(' ', "") else text.dropLast(1)
                    onChange(text)
                }
                Keys.V -> if (Keys.down(Keys.LCONTROL)) {
                    text = (text + (Minecraft.getInstance().keyboardHandler.clipboard ?: "")).take(max)
                    onChange(text)
                } else return false
                else -> return false
            }
            return true
        }
    }

    /** Draws [f]'s text from [x] with the caret when focused; [placeholder] when empty. */
    fun fieldText(f: Field, x: Float, cy: Float, w: Float, font: WebText, placeholder: String, color: Int = Tok.T1) {
        val focusedNow = focused === f
        if (f.text.isEmpty() && !focusedNow) {
            text(font, fit(font, placeholder, w), x, cy, Tok.T3)
            return
        }
        var shown = f.text
        while (shown.isNotEmpty() && font.width(shown) > w - 3f) shown = shown.drop(1)
        if (f.text.isEmpty()) text(font, fit(font, placeholder, w), x, cy, Tok.T4)
        text(font, shown, x, cy, color)
        if (focusedNow && System.currentTimeMillis() % 1000 < 530) box(x + font.width(shown) + 0.5f, cy - 3.5f, 0.75f, 7f, 0f, Tok.a1)
    }
}
