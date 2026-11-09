package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.TitleScreenInitEvent
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Keys
import dev.horizon.client.gui.modern.ModernWindows
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.gui.modern.Type
import dev.horizon.client.gui.modern.Ui
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Icon
import dev.horizon.client.render.WebText
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import kotlin.math.floor

/**
 * The client's own title screen ([Menus]). It extends vanilla's, so vanilla's checks for the title
 * screen still hold, but draws none of it. Over the animated backdrop (no panel; only the rows sit on a quiet card), a
 * column down the left holds the logo (its letters white, its horizon lines a softer white), a greeting and the
 * menu as the new ClickGUI's sidebar rows (design/clickgui: icon and label, the pill and chevron on hover), under Play
 * (Rejoin, the last server joined, once there is one; vanilla's actions; and Proxies) and Game. The versions are in the bottom-left corner and Mojang's copyright line
 * (opening the credits, as vanilla's does) in the bottom-right. Everything fades and slides in the first time a menu is
 * shown, quicker when coming back to it from the screens it opens; on a short screen the column is scaled down to fit.
 */
class MainMenu : TitleScreen(), MenuScreen {
    private val mc get() = Minecraft.getInstance()

    /** One of the menu's rows: an icon and a label; hovered, the ClickGUI's nav pill slides in behind it. */
    private class Row(val label: String, val icon: String, val sound: () -> Unit = Menus::open, val action: () -> Unit) {
        /** Grey text at the row's right end. */
        var detail = ""
        val hover = Animation(Easing.OUT_EXPO, 300)
        var x = 0f
        var y = 0f
        var w = 0f
        fun contains(mx: Float, my: Float) = mx >= x && mx <= x + w && my >= y && my <= y + ROW_H
    }

    /** Not in vanilla: joins the server last joined (or tried), in one click; shown once there is one. */
    private val rejoin = Row("Rejoin", "play") { rejoin() }

    private val play = listOf(
        Row("Singleplayer", "player") { mc.gui.setScreen(SelectWorldScreen(this)) },
        Row("Multiplayer", "users") { mc.gui.setScreen(if (mc.options.skipMultiplayerWarning) JoinMultiplayerScreen(this) else SafetyScreen(this)) },
        // Not in vanilla: the Windows screen's proxies, so one can be picked before the first connection.
        Row("Proxies", "globe") { ModernWindows.openProxies(this) },
    )
    private val game = listOf(
        Row("Options", "gear") { mc.gui.setScreen(OptionsScreen(this, mc.options)) },
        Row("Quit", "power", Menus::back) { mc.stop() },
    )
    private val rows get() = sections().flatMap { it.second }

    private fun sections(): List<Pair<String, List<Row>>> {
        val name = Storage.shared.lastServerName.ifBlank { Storage.shared.lastServerIp }
        rejoin.detail = name
        val playRows = if (Storage.shared.lastServerIp.isBlank()) play else listOf(rejoin) + play
        return listOf("Play" to playRows, "Game" to game)
    }

    /** Connects to the last server: its entry in the server list when it has one (its settings, its icon), else as typed. */
    private fun rejoin() {
        val ip = Storage.shared.lastServerIp
        val saved = ServerList(mc).also { it.load() }.get(ip)
        val server = saved ?: ServerData(Storage.shared.lastServerName.ifBlank { ip }, ip, ServerData.Type.OTHER)
        ConnectScreen.startConnecting(JoinMultiplayerScreen(this), mc, ServerAddress.parseString(ip), server, false, null)
    }

    private val copyright = Component.translatable("title.credits").string
    private val versions = "Horizon $VERSION  -  Minecraft ${SharedConstants.getCurrentVersion().name()}"
    private var copyrightHovered = false

    /** Whether ([x], [y]) is on the copyright line, bottom right (checked on the click itself, not the last frame). */
    private fun overCopyright(x: Float, y: Float): Boolean {
        val cw = Type.small.width(copyright)
        return x >= width - cw - 8f && x <= width - 8f && y >= height - 14f && y <= height - 3f
    }
    private var openedAt = 0L
    private var visits = 0
    private var scale = 1f

    // Vanilla's buttons, splash, logo and Realms notifications are all left out.
    override fun init() {
        TitleScreenInitEvent.cancelled = false
        EventBus.post(TitleScreenInitEvent)
    }

    // The first time it's shown everything comes in one after another; coming back to it, quicker and nearly together.
    override fun added() {
        openedAt = System.currentTimeMillis()
        visits++
    }

    override fun removed() {}

    override fun tick() {}

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractPanorama(graphics, delta)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {}

    /** How far element [i] is into its entrance (0..1), each starting a little after the one before. */
    private fun entrance(i: Int): Float {
        val first = visits <= 1
        val t = (System.currentTimeMillis() - openedAt - i * (if (first) 55L else 18L)) / (if (first) 750.0 else 350.0)
        return Easing.OUT_EXPO.apply(t.coerceIn(0.0, 1.0)).toFloat()
    }


    override fun draw(mouseX: Float, mouseY: Float) {
        // At startup the loading screen fades out over this one; the entrance waits for it.
        if (mc.gui.overlay() != null) openedAt = System.currentTimeMillis()
        val sw = width.toFloat()
        val sh = height.toFloat()

        // The menu wants MIN_H of height; on a shorter screen it's drawn smaller.
        scale = minOf(1f, sh / MIN_H)
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.scale(scale, scale)
        menu(sh / scale, mouseX / scale, mouseY / scale)
        pose.popMatrix()

        // The versions bottom left, the copyright line (opening the credits) bottom right, at full size.
        Canvas.alpha = entrance(9)
        val font = Type.small
        font.drawMiddle(versions, 8f, sh - 8.5f, Tok.T3)
        val cw = font.width(copyright)
        copyrightHovered = overCopyright(mouseX, mouseY)
        if (copyrightHovered) Cursor.request(CursorTypes.POINTING_HAND)
        font.drawMiddle(copyright, sw - cw - 8f, sh - 8.5f, if (copyrightHovered) Tok.T1 else Tok.T3)
        Canvas.alpha = 1f
    }

    /** The logo, the greeting and the rows down the left, centred in [h] (the scaled height), the mouse at [mx], [my]. */
    private fun menu(h: Float, mx: Float, my: Float) {
        val sections = sections()
        val rows = sections.flatMap { it.second }
        val block = LOGO_H + 14f + 8f + 24f + 2f * SECTION + rows.size * ROW_H + (rows.size - 2) * ROW_GAP + SECTION_GAP
        var y = floor(maxOf(14f, (h - block) / 2f - 4f))
        val x = X

        // The logo: its letters in white, its horizon lines a softer white. Like the backdrop, the title screen has no hue.
        var k = entrance(1)
        Canvas.alpha = k
        val lx = floor(x + 4f - (1f - k) * 12f)
        val ly = floor(y)
        wordmark.draw(lx, ly, LOGO_W, LOGO_H, Tok.T1, Tok.T1, 1f)
        rules.draw(lx, ly, LOGO_W, LOGO_H, Tok.T2, Tok.T2, 1f)
        y += LOGO_H + 14f

        k = entrance(2)
        Canvas.alpha = k
        val hi = "Welcome back, "
        val gx = x + 5f - (1f - k) * 12f
        Type.label.drawMiddle(hi, gx, y, Tok.T3)
        Type.label.drawMiddle(Ui.fit(Type.label, mc.user.name, ROW_W - 10f - Type.label.width(hi)), gx + Type.label.width(hi), y, Tok.T1)
        y += 8f + 24f

        // Behind the rows only, a quiet card: the blurred backdrop, a faint white wash and a hairline border.
        k = entrance(3)
        Canvas.alpha = k
        val ch = 2f * SECTION + rows.size * ROW_H + (rows.size - 2) * ROW_GAP + SECTION_GAP + CARD_PAD + 2f
        val cx = floor(x - CARD_PAD - (1f - k) * 12f)
        val cy = floor(y - CARD_PAD + 2f)
        val cw = ROW_W + 2f * CARD_PAD
        Ui.glow(cx, cy + 6f, cw, ch, 10f, 22f, 0x59000000)
        Ui.blur(cx, cy, cw, ch, 10f)
        Ui.vbox(cx, cy, cw, ch, 10f, 0x1A16161A, 0x26101013, 0.5f, 0x14FFFFFF)
        Ui.vbox(cx, cy, cw, ch, 10f, 0x0AFFFFFF, 0x04FFFFFF)
        Ui.insetTop(cx, cy, cw, 10f, 0x0DFFFFFF)

        var i = 4
        for ((title, list) in sections) {
            k = entrance(i++)
            Canvas.alpha = k
            Type.section.drawMiddle(title.uppercase(), x + 9f - (1f - k) * 12f, y + SECTION / 2f, Tok.T4)
            y += SECTION
            for (r in list) {
                k = entrance(i++)
                Canvas.alpha = k
                row(r, x - (1f - k) * 12f, y, mx, my)
                y += ROW_H + ROW_GAP
            }
            y += SECTION_GAP - ROW_GAP
        }
        Canvas.alpha = 1f
    }

    /** A row as the ClickGUI's sidebar draws one: hovered, its pill and chevron fade in and it slides over a little. */
    private fun row(r: Row, x: Float, y: Float, mx: Float, my: Float) {
        r.x = x
        r.y = y
        r.w = ROW_W
        val over = r.contains(mx, my)
        if (over) Cursor.request(CursorTypes.POINTING_HAND)
        r.hover.update(if (over) 1f else 0f)
        val k = r.hover.value
        if (k > 0.005f) {
            // #pill, in white instead of the accent: linear-gradient(90deg, white 10%, white 4.5%), 1px white 9% border.
            val a = Canvas.alpha
            Canvas.alpha = a * k
            Ui.glow(x, y, ROW_W, ROW_H, 6f, 9f, 0x1AFFFFFF)
            Ui.hbox(x, y, ROW_W, ROW_H, 6f, 0x1AFFFFFF, 0x0BFFFFFF, 0.5f, 0x17FFFFFF)
            Canvas.alpha = a
        }
        val slide = 2f * k
        val ic = Colors.lerp(Tok.T3, Tok.T1, k)
        Ui.icon(r.icon, x + 9f + slide, y + (ROW_H - ICON) / 2f, ICON, ic)
        val lx = x + 9f + ICON + 8f + slide
        LABEL.drawMiddle(r.label, lx, y + ROW_H / 2f, Colors.lerp(Tok.T2, Tok.T1, k))
        if (r.detail.isNotEmpty()) {
            // Right-aligned, short of where the chevron comes in.
            val right = x + ROW_W - 9f - 7f - 6f
            val left = lx + LABEL.width(r.label) + 10f
            val d = Ui.fit(Type.label, r.detail, right - left)
            Type.label.drawMiddle(d, right - Type.label.width(d), y + ROW_H / 2f, Tok.T3)
        }
        if (k > 0.005f) Ui.icon("chev", x + ROW_W - 9f - 7f - 2f * (1f - k), y + (ROW_H - 7f) / 2f, 7f, Colors.withAlpha(Tok.T3, Colors.alphaF(Tok.T3) * k))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != Keys.MOUSE_LEFT) return false
        val mx = event.x().toFloat() / scale
        val my = event.y().toFloat() / scale
        for (r in rows) if (r.contains(mx, my)) {
            r.sound()
            r.action()
            return true
        }
        if (overCopyright(event.x().toFloat(), event.y().toFloat())) {
            Menus.open()
            mc.gui.setScreen(CreditsAndAttributionScreen(this))
            return true
        }
        return false
    }


    private companion object {
        // wordmark.svg (the logo's letters) and wordmark-rules.svg (its lines) share an 834 x 196 box; whole numbers, or
        // Icon rasterises them at 1x (blurry, then pixelated).
        const val LOGO_W = 170f
        const val LOGO_H = 40f
        val wordmark by lazy { Icon("wordmark", LOGO_W, LOGO_H) }
        val rules by lazy { Icon("wordmark-rules", LOGO_W, LOGO_H) }

        // In GUI units (the mockup's CSS px / 2): the column 80px in from the left, its rows 360 x 44.
        const val X = 40f
        const val ROW_W = 180f
        const val ROW_H = 22f
        const val ROW_GAP = 3f
        const val ICON = 9f
        const val SECTION = 14f
        const val SECTION_GAP = 12f
        const val CARD_PAD = 7f
        const val MIN_H = 300f
        val LABEL by lazy { WebText("sf_medium", 7.5f) }


        val VERSION: String = FabricLoader.getInstance().getModContainer(HorizonClient.ID).map { it.metadata.version.friendlyString }.orElse("")
    }
}
