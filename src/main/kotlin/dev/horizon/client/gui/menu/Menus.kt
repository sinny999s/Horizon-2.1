package dev.horizon.client.gui.menu

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.horizon.client.event.EventBus
import dev.horizon.client.event.ScreenEvent
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Sounds
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.Fonts
import dev.horizon.client.render.TextRenderer
import dev.horizon.client.render.Theme
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.network.chat.contents.TranslatableContents

/** Not in the original: one of the client's menu screens; Overlay draws it in the client's 2D layer each frame. */
interface MenuScreen {
    fun draw(mouseX: Float, mouseY: Float)
}

/**
 * Not in the original: the client's menus in place of vanilla's title screen, world list, server list, options and pause
 * menu, wherever vanilla opens them (and, out of a world, the multiplayer warning and Credits, as [PanelMenu]s, and the
 * disconnected screen, as a [DisconnectMenu]). All but the title screen wrap the vanilla screen they replace; vanilla
 * code going back to that screen gets the same wrapper again. (A pause screen without its menu, from F3 + Esc, stays vanilla's.)
 */
object Menus {
    private var worlds: WorldsScreen? = null
    private var servers: ServersScreen? = null
    private var options: OptionsMenu? = null
    private var pause: PauseMenu? = null

    fun init() {
        EventBus.on<ScreenEvent>(this) { e ->
            val mc = Minecraft.getInstance()
            val screen = e.screen
            val menu: Screen = when {
                // Closing a screen out of a world: setScreen makes vanilla's title screen itself, after this event.
                screen == null -> if (mc.level == null) MainMenu() else return@on
                screen.javaClass == TitleScreen::class.java -> MainMenu()
                // Simple vanilla screens the menus lead to: in the menus' style ([PanelMenu]).
                mc.level == null && (screen.javaClass == SafetyScreen::class.java || screen.javaClass == CreditsAndAttributionScreen::class.java) ->
                    PanelMenu(screen)
                screen is DisconnectedScreen && screen.javaClass == DisconnectedScreen::class.java -> DisconnectMenu(screen)
                // Add / Edit Server and Direct Connection: [ServerEditMenu].
                screen.javaClass == net.minecraft.client.gui.screens.ManageServerScreen::class.java ||
                    screen.javaClass == net.minecraft.client.gui.screens.DirectJoinServerScreen::class.java -> ServerEditMenu(screen)
                screen is net.minecraft.client.gui.screens.ConnectScreen && screen.javaClass == net.minecraft.client.gui.screens.ConnectScreen::class.java -> ConnectMenu(screen)
                screen is SelectWorldScreen && screen.javaClass == SelectWorldScreen::class.java ->
                    worlds?.takeIf { it.vanilla === screen } ?: WorldsScreen(screen).also { worlds = it }
                screen is JoinMultiplayerScreen && screen.javaClass == JoinMultiplayerScreen::class.java ->
                    servers?.takeIf { it.vanilla === screen } ?: ServersScreen(screen).also { servers = it }
                screen is OptionsScreen && screen.javaClass == OptionsScreen::class.java ->
                    options?.takeIf { it.vanilla === screen } ?: OptionsMenu(screen).also { options = it }
                screen is PauseScreen && screen.javaClass == PauseScreen::class.java && screen.showsPauseMenu() ->
                    pause?.takeIf { it.vanilla === screen } ?: PauseMenu(screen).also { pause = it }
                else -> return@on
            }
            e.cancel()
            mc.gui.setScreen(menu)
        }
    }

    // The original's GUI sounds, as the ClickGUI uses them.
    /** Opening something (a screen, a page, a popup). */
    fun open() = Sounds.SETTINGS_OPEN.play()

    /** Going back or leaving. */
    fun back() = Sounds.SETTINGS_CLOSE.play()

    /** A switch flipped. */
    fun toggle(on: Boolean) = (if (on) Sounds.ENABLE else Sounds.DISABLE).play()

    /** Stepping a value or a page forward or back. */
    fun step(forward: Boolean) = (if (forward) Sounds.SWITCH_RIGHT else Sounds.SWITCH_LEFT).play()

    /** The sound for a vanilla button: going back for the ones that leave, opening for the rest. */
    fun soundFor(button: AbstractButton): () -> Unit =
        if ((button.message.contents as? TranslatableContents)?.key in LEAVING) ::back else ::open

    private val LEAVING = setOf("gui.back", "gui.done", "gui.cancel", "menu.returnToGame", "menu.disconnect", "menu.returnToMenu")

    /** A list row's pane: lighter when hovered, outlined in the theme colour when selected. */
    fun card(x: Float, y: Float, w: Float, h: Float, hovered: Boolean, selected: Boolean) {
        val tint = when {
            selected -> 0xFF646464.toInt()
            hovered -> 0xFF545454.toInt()
            else -> 0xFF3A3A3A.toInt()
        }
        val outline = when {
            selected -> OUTLINE
            hovered -> 0x50FFFFFF
            else -> 0x22FFFFFF
        }
        glass(x, y, w, h, 6f, tint, outline)
    }

    /**
     * Runs [draw] scaled to half screen pixels, handing it the factor to multiply its coordinates by. The original's rounded
     * shapes soften their edges over 2 of the units they're drawn in: 4 screen pixels at GUI scale 2, which smears
     * small shapes (switches, knobs); in half pixels it's one. Those shapes also come out one unit wider and taller
     * than asked (their quad is padded 1 left/top and 2 right/bottom, with the shape mapped across it), which in half
     * pixels puts the right and bottom edges, and their outline, between pixels: the helpers ask for one unit less.
     */
    private inline fun crisp(draw: (Float) -> Unit) {
        val k = Minecraft.getInstance().window.guiScale * 2f
        Canvas.pose.pushMatrix()
        Canvas.pose.scale(1f / k, 1f / k)
        draw(k)
        Canvas.pose.popMatrix()
    }

    /** A rounded rect with crisp edges. */
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) =
        crisp { k -> Canvas.rect(x * k, y * k, w * k - 1f, h * k - 1f, radius * k, color) }

    /** A rounded rect with crisp edges and a colour per corner (bottom-left, top-left, bottom-right, top-right). */
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, bl: Int, tl: Int, br: Int, tr: Int) =
        crisp { k -> Canvas.rect(x * k, y * k, w * k - 1f, h * k - 1f, radius * k, bl, tl, br, tr) }

    /**
     * A pane of the blurred backdrop tinted by [tint] (opaque), with crisp edges and a one-pixel [outline]. The outline is
     * a filled shape with the pane a pixel inside it: the glass shader's own border is a thin band that, on the curves,
     * falls between pixels at part strength, so its corners came out dimmer than its sides.
     */
    fun glass(x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Int, outline: Int) = crisp { k ->
        val px = 2f
        if (outline ushr 24 != 0) Canvas.rect(x * k, y * k, w * k - 1f, h * k - 1f, radius * k, outline)
        Canvas.glass(x * k + px, y * k + px, w * k - 1f - 2 * px, h * k - 1f - 2 * px, maxOf(0f, radius * k - px), 0f, 0f, 1f, tint, outline)
    }

    /** [texture] in a rounded rect with crisp corners. */
    fun image(texture: GpuTextureView, x: Float, y: Float, w: Float, h: Float, radius: Float) =
        crisp { k -> Canvas.texturedRect(texture, x * k, y * k, w * k - 1f, h * k - 1f, radius * k, 0f, 0f, 1f, -1, 0) }

    /** [text] cut to [max] px with "..." when wider. */
    fun fit(text: String, font: TextRenderer, max: Float): String {
        if (font.width(text) <= max) return text
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end) + "...") > max) end--
        return text.substring(0, end).trimEnd() + "..."
    }

    /** "..." filling up over 1.2 s, for things still loading. */
    fun dots(): String = ".".repeat(1 + (System.currentTimeMillis() % 1200 / 400).toInt())

    const val GREY = 0xFF9A9A9A.toInt()

    /**
     * The menus' accent, in place of the theme's colours: like the title screen and its backdrop, the screens out of a
     * world are black through white whatever the theme. [ACCENT] for a selection or a hovered arrow, [FILL] for the
     * filled part of a switch or slider, [OUTLINE] round a selected or hovered pane.
     */
    const val ACCENT = 0xFFF0F0F2.toInt()
    const val FILL = 0xFFD2D2D6.toInt()
    const val OUTLINE = 0x8CFFFFFF.toInt()
}

/**
 * A rounded pane of the blurred backdrop with a centred label. Hovered, it lightens, its outline turns the theme colour
 * and the label brightens; an inactive one is dimmed and can't be clicked.
 */
class MenuButton(var label: String, private val sound: () -> Unit = Menus::open, private val action: (MouseButtonEvent) -> Unit) {
    var active = true
    private var x = 0f
    private var y = 0f
    private var w = 0f
    private var h = 0f
    private val hover = Animation(Easing.OUT_CUBIC, 180)

    fun contains(mx: Float, my: Float) = mx >= x && mx <= x + w && my >= y && my <= y + h

    fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, font: TextRenderer = Fonts.medium95) {
        this.x = x
        this.y = y
        this.w = w
        this.h = h
        val hovered = active && contains(mouseX, mouseY)
        if (hovered) Cursor.request(CursorTypes.POINTING_HAND)
        hover.update(if (hovered) 1f else 0f)
        val k = hover.value
        // (Glass, not a plain rect: the rect shader fades its border with a translucent fill.)
        Menus.glass(x, y, w, h, 5f, Colors.lerp(0xFF4A4A4A.toInt(), 0xFF8C8C8C.toInt(), k), Colors.lerp(0x30FFFFFF, Menus.OUTLINE, k))
        val color = if (active) Colors.lerp(0xFFC8C8C8.toInt(), Theme.text, k) else 0x66C8C8C8
        font.drawMiddleCentered(Menus.fit(label, font, w - 8f), x + w / 2f, y + h / 2f, color)
    }

    /** Runs the action if [event] is on this button and it's active. */
    fun click(event: MouseButtonEvent): Boolean {
        if (!active || !contains(event.x().toFloat(), event.y().toFloat())) return false
        sound()
        action(event)
        return true
    }
}
