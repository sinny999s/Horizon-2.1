package dev.horizon.client

import dev.horizon.client.render.Wordmark
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil

/**
 * Pre-launch splash: a small dark card with the wordmark, whose horizon line fills up with the client's real start-up
 * stages ([stage]), what it is doing and the version. Grey only, like the title screen. It fades in when the game
 * starts loading and fades out once the client has initialised ([hide]).
 */
object PreLaunch : PreLaunchEntrypoint {
    private var frame: JFrame? = null
    private var timer: Timer? = null

    override fun onPreLaunch() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        SwingUtilities.invokeLater { show() }
    }

    /** Reports what the client is doing now and how far along start-up is (0..1); safe from any thread. */
    fun stage(text: String, progress: Float) = LoadingPanel.setStage(text, progress)

    private fun show() {
        val f = JFrame("Horizon")
        f.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        f.isUndecorated = true
        f.isResizable = false

        // A see-through window gets a soft shadow around the card and smooth corners; otherwise the window is cut to
        // the card's shape (hard corners, no shadow).
        val translucent = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        } catch (ignored: Exception) { false }
        if (translucent) {
            try { f.background = Color(0, 0, 0, 0) } catch (ignored: Exception) { LoadingPanel.margin = 0 }
        } else LoadingPanel.margin = 0
        LoadingPanel.preferredSize = Dimension(Splash.W + 2 * LoadingPanel.margin, Splash.H + 2 * LoadingPanel.margin)
        f.contentPane.add(LoadingPanel)
        f.pack()
        f.setLocationRelativeTo(null)
        if (LoadingPanel.margin == 0) {
            try { f.shape = RoundRectangle2D.Float(0f, 0f, Splash.W.toFloat(), Splash.H.toFloat(), Splash.R * 2, Splash.R * 2) } catch (ignored: Exception) {}
        }

        // drag the card anywhere
        var dragPoint: Point? = null
        LoadingPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragPoint = e.point
            }
        })
        LoadingPanel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val p = dragPoint ?: return
                f.setLocation(e.xOnScreen - p.x, e.yOnScreen - p.y)
            }
        })

        try { f.opacity = 0f } catch (ignored: Exception) {}
        f.isVisible = true
        frame = f

        val t = Timer(16) {
            LoadingPanel.tick()
            try { f.opacity = LoadingPanel.windowOpacity() } catch (ignored: Exception) {}
            LoadingPanel.repaint()
            if (LoadingPanel.finished()) dispose()
        }
        t.isRepeats = true
        t.start()
        timer = t
    }

    private fun dispose() {
        timer?.stop()
        frame?.dispose()
        timer = null
        frame = null
    }

    /** Starts the fade-out; the window is disposed when it is done (the AWT thread must not outlive the game). */
    fun hide() {
        SwingUtilities.invokeLater {
            if (frame == null) return@invokeLater
            LoadingPanel.startClosing()
        }
    }
}

/** The splash window's content: start-up state, hover and fades; [Splash] draws it. */
object LoadingPanel : JPanel() {
    private fun readResolve(): Any = LoadingPanel

    /** Room for the shadow around the card, 0 when the window can't be see-through. */
    var margin = Splash.SHADOW

    private val version = FabricLoader.getInstance().getModContainer("horizon")
        .map { it.metadata.version.friendlyString }.orElse("")

    private var ticks = 0
    private var closing = -1
    private var mouseX = -1
    private var mouseY = -1
    private var cardHover = 0f
    private var closeHover = 0f

    @Volatile private var status = "Starting up"
    @Volatile private var target = 0.04f
    private var shown = 0f

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (Splash.inClose(e.x - margin, e.y - margin)) PreLaunch.hide()
            }

            override fun mouseExited(e: MouseEvent) {
                mouseX = -1
                mouseY = -1
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                mouseX = e.x - margin
                mouseY = e.y - margin
            }
        })
    }

    fun setStage(text: String, progress: Float) {
        status = text
        target = progress.coerceIn(0f, 1f)
    }

    fun tick() {
        ticks++
        shown += (target - shown) * 0.12f
        val onCard = mouseX in 0 until Splash.W && mouseY in 0 until Splash.H
        cardHover += ((if (onCard) 1f else 0f) - cardHover) * 0.2f
        closeHover += ((if (Splash.inClose(mouseX, mouseY)) 1f else 0f) - closeHover) * 0.3f
        if (closing >= 0) closing++
    }

    fun startClosing() {
        if (closing < 0) closing = 0
    }

    fun windowOpacity(): Float {
        val fadeIn = (ticks / 14f).coerceIn(0f, 1f)
        val fadeOut = if (closing >= 0) 1f - (closing / 14f) else 1f
        return (fadeIn * fadeOut).coerceIn(0f, 1f)
    }

    fun finished() = closing >= 14

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(margin, margin)
            Splash.paint(g2, Splash.State(status, shown, version, cardHover, closeHover, margin > 0))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Draws the splash card at (0, 0) of a [Graphics2D] in its own units ([W] x [H]), the shadow (if any) around it.
 * It reads no game or window state, so it can be drawn into an image off screen.
 */
object Splash {
    const val W = 360
    const val H = 142
    const val R = 14f
    const val SHADOW = 18

    // 200 x 47 keeps the wordmark's proportions (Wordmark.W x H). Its lower horizon line is the progress line.
    private const val LOGO_W = 200
    private const val LOGO_H = 47
    private const val LOGO_X = (W - LOGO_W) / 2
    private const val LOGO_Y = 34
    private const val BAR_X = LOGO_X + LOGO_W * Wordmark.LINE_X0
    private const val BAR_W = LOGO_W * (Wordmark.LINE_X1 - Wordmark.LINE_X0)
    private const val BAR_Y = LOGO_Y + 45f
    private const val TEXT_Y = BAR_Y + 26f

    // The ClickGUI's text tokens (Ui.Tok T1..T4) over the card.
    private val T1 = Color(0xF5, 0xF5, 0xF7)
    private val T2 = Color(0xEB, 0xEB, 0xF5, 0xA3)
    private val T3 = Color(0xEB, 0xEB, 0xF5, 0x61)
    private val T4 = Color(0xEB, 0xEB, 0xF5, 0x38)

    /** What one frame shows; [cardHover] and [closeHover] are eased 0..1. */
    data class State(
        val status: String,
        val progress: Float,
        val version: String,
        val cardHover: Float = 0f,
        val closeHover: Float = 0f,
        val shadow: Boolean = true,
    )

    private var logo: BufferedImage? = null
    private var logoScale = 0.0
    private var logoFailed = false

    /** The close button's spot, top right, in card units. */
    fun inClose(x: Int, y: Int) = x in (W - 34)..(W - 10) && y in 10..34

    fun paint(g: Graphics2D, s: State) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        if (s.shadow) shadow(g)

        // The card: near black, a hairline border and a faint highlight along the top edge.
        val card = RoundRectangle2D.Float(0f, 0f, W.toFloat(), H.toFloat(), R * 2, R * 2)
        g.paint = GradientPaint(0f, 0f, Color(0x16, 0x16, 0x18), 0f, H.toFloat(), Color(0x0F, 0x0F, 0x11))
        g.fill(card)
        g.stroke = BasicStroke(1f)
        g.color = Color(255, 255, 255, 0x14)
        g.draw(RoundRectangle2D.Float(0.5f, 0.5f, W - 1f, H - 1f, R * 2 - 1f, R * 2 - 1f))
        g.color = Color(255, 255, 255, 0x0D)
        g.draw(Line2D.Float(R, 1.5f, W - R, 1.5f))

        // The wordmark, its letters in white and its lines softer, as on the title screen.
        val img = logo(g.transform.scaleX)
        if (img != null) g.drawImage(img, LOGO_X, LOGO_Y, LOGO_W, LOGO_H, null)
        else {
            g.font = Font("Segoe UI Semibold", Font.PLAIN, 30)
            g.color = T1
            val w = g.fontMetrics.stringWidth("Horizon")
            g.drawString("Horizon", (W - w) / 2f, LOGO_Y + 36f)
        }

        // Progress: the wordmark's horizon line fills up from the left.
        val x = BAR_X
        val bw = BAR_W
        g.color = Color(255, 255, 255, 0x14)
        g.fill(RoundRectangle2D.Float(x, BAR_Y, bw, 2f, 2f, 2f))
        val fw = bw * s.progress.coerceIn(0f, 1f)
        if (fw > 0.5f) {
            g.color = T1
            g.fill(RoundRectangle2D.Float(x, BAR_Y, fw, 2f, 2f, 2f))
        }

        // What it is doing on the left, the version on the right.
        g.font = Font("Segoe UI", Font.PLAIN, 11)
        val fm = g.fontMetrics
        val ver = if (s.version.isEmpty()) "" else "v" + s.version
        val vw = fm.stringWidth(ver)
        g.color = T3
        g.drawString(ver, x + bw - vw, TEXT_Y)
        g.color = T2
        g.drawString(fit(fm, s.status, bw - vw - 12f), x, TEXT_Y)

        // Close: only while the mouse is on the card.
        if (s.cardHover > 0.01f) {
            val cx = W - 22f
            val cy = 22f
            if (s.closeHover > 0.01f) {
                g.color = Color(255, 255, 255, (0x14 * s.closeHover).toInt())
                g.fill(java.awt.geom.Ellipse2D.Float(cx - 11f, cy - 11f, 22f, 22f))
            }
            g.color = mix(T4, T1, s.closeHover, s.cardHover)
            g.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Float(cx - 4f, cy - 4f, cx + 4f, cy + 4f))
            g.draw(Line2D.Float(cx + 4f, cy - 4f, cx - 4f, cy + 4f))
        }
    }

    /** A soft shadow, a little below the card: rings of black that fade out over [SHADOW] units. */
    private fun shadow(g: Graphics2D) {
        for (k in SHADOW downTo 1) {
            val t = 1f - (k - 1f) / SHADOW
            g.color = Color(0, 0, 0, (t * t * 9f).toInt().coerceIn(0, 255))
            val d = k.toFloat()
            g.fill(RoundRectangle2D.Float(-d, -d + 4f, W + 2 * d, H + 2 * d, (R + d) * 2, (R + d) * 2))
        }
    }

    /** [text] cut to [width] with an ellipsis. */
    private fun fit(fm: FontMetrics, text: String, width: Float): String {
        if (fm.stringWidth(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth(t.trimEnd(' ', '.') + "...") > width) t = t.dropLast(1)
        return t.trimEnd(' ', '.') + "..."
    }

    private fun mix(a: Color, b: Color, k: Float, alpha: Float): Color {
        fun l(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return Color(l(a.red, b.red), l(a.green, b.green), l(a.blue, b.blue), (l(a.alpha, b.alpha) * alpha).toInt().coerceIn(0, 255))
    }

    /** The wordmark at the screen's [scale]: letters white, the upper line at T2's alpha. Null if it won't load. */
    private fun logo(scale: Double): BufferedImage? {
        if (logoFailed) return null
        if (logo != null && logoScale == scale) return logo
        return try {
            val img = Wordmark.raster(ceil(LOGO_W * scale).toInt(), ceil(LOGO_H * scale).toInt(), T2.alpha / 255f)
            logo = img
            logoScale = scale
            img
        } catch (t: Throwable) {
            logoFailed = true
            null
        }
    }
}
