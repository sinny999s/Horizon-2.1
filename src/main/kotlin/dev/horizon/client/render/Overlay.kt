package dev.horizon.client.render

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.gui.Cursor
import dev.horizon.client.gui.Fps
import dev.horizon.client.gui.menu.MenuScreen
import dev.horizon.client.gui.modern.ModernGui
import dev.horizon.client.hud.Hud
import dev.horizon.client.hud.Notifications
import dev.horizon.client.hud.WaypointRenderer
import dev.horizon.client.module.cosmetic.BetterChat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The client's 2D layer, drawn after vanilla's GUI each frame (from GuiRendererMixin), in two layers,
 * each followed by the vanilla items it submitted and then its [late] draws. First the world's: the background blur is
 * captured (world + vanilla HUD only), then the notifications and HUD widgets (nametags and their armour and effects
 * among them). Then the client's screens (ClickGUI, windows, HUD editor, menus) over all of that, so nothing the HUD
 * submits draws over them.
 */
object Overlay {
    private val lateDraws = ArrayList<() -> Unit>()

    /** Draws after the vanilla items submitted this frame by the same layer. */
    fun late(draw: () -> Unit) {
        lateDraws += draw
    }

    /** The world's layer: HUD, nametags, notifications. */
    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val mc = Minecraft.getInstance()
        Fps.frame()
        Canvas.beginFrame(graphics)
        if (mc.player != null && mc.level != null) {
            Theme.update()
            Blur.capture()
            World3D.composite()
            // Better Chat's pane, and (chat closed) vanilla's chat over it.
            BetterChat.renderLayer(graphics)
            EventBus.post(Render2DEvent(graphics))
            WaypointRenderer.render()
            Notifications.render()
            Hud.render()
        }
        Canvas.flush(mc.gameRenderer.mainRenderTarget())
    }

    /** After the world layer's vanilla items: its late draws. */
    fun renderLate() {
        runLate()
    }

    /** The screens' layer, over the world's and its items. */
    fun renderScreens(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mc = Minecraft.getInstance()
        Canvas.beginFrame(graphics)
        val screen = mc.gui.screen()
        if (screen === ModernGui) ModernGui.draw()
        if (screen === dev.horizon.client.gui.modern.LunarLockerScreen) dev.horizon.client.gui.modern.LunarLockerScreen.draw(graphics)
        if (screen === dev.horizon.client.gui.modern.LunarFriendsScreen) dev.horizon.client.gui.modern.LunarFriendsScreen.draw()
        if (screen === dev.horizon.client.gui.modern.PublicConfigsScreen) dev.horizon.client.gui.modern.PublicConfigsScreen.draw()
        if (screen === dev.horizon.client.gui.modern.LunarEmoteWheel) dev.horizon.client.gui.modern.LunarEmoteWheel.draw()
        if (screen === dev.horizon.client.gui.popup.PopupScreen) dev.horizon.client.gui.popup.PopupScreen.draw()
        if (screen === dev.horizon.client.gui.modern.ModernWindows) dev.horizon.client.gui.modern.ModernWindows.draw()
        if (screen === dev.horizon.client.gui.modern.ModernHudEditor) dev.horizon.client.gui.modern.ModernHudEditor.draw()
        if (screen is MenuScreen) {
            // Out of a world nothing above moves the theme along or captures the blur the menus' panes show.
            if (mc.player == null) {
                Theme.update()
                Blur.capture()
            }
            screen.draw(mouseX.toFloat(), mouseY.toFloat())
        }
        Canvas.flush(mc.gameRenderer.mainRenderTarget())
    }

    /** After the screens' vanilla items: their late draws, then the end of the frame. */
    fun renderScreensLate() {
        runLate()
        Canvas.endFrame()
        World3D.endFrame()
        Cursor.endFrame()
    }

    private fun runLate() {
        val mc = Minecraft.getInstance()
        val draws = ArrayList(lateDraws)
        lateDraws.clear()
        for (d in draws) d()
        Canvas.flush(mc.gameRenderer.mainRenderTarget())
    }
}
