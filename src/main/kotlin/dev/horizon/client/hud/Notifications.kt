package dev.horizon.client.hud

import dev.horizon.client.gui.Sounds
import dev.horizon.client.gui.modern.Tok
import dev.horizon.client.hud.widgets.NotificationsWidget
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import dev.horizon.client.render.Easing
import dev.horizon.client.render.VectorIcon
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

/** Notification types and their icons. */
enum class NotificationType(iconName: String) {
    SUCCESS("success"), INFO("info"), WARNING("warning"), ERROR("error"), ENABLED("on"), DISABLED("off");

    /** Drawn in the theme colour, as the widgets' icons are. */
    val vicon by lazy { VectorIcon(iconName, 10f, mask = true) }
}

/**
 * Centred plates below the crosshair, the newest on top pushing older ones down,
 * at most 8. Shown only while the Notifications widget is on; module toggles post one and play the toggle sound
 * through it, so with the widget off toggles are silent (on purpose).
 * The plates take the widgets' new look (the one-line widget's layout).
 */
object Notifications {
    private class Notification(val title: String, val content: String, val type: NotificationType, val duration: Long,
                               val item: ItemStack?, startY: Float) {
        var y = startY
        val anim = Animation(Easing.OUT_BACK, 300)
        val start = System.currentTimeMillis()
    }

    private val list = ArrayList<Notification>()

    /** Suppresses notifications while set. */
    var silent = false

    fun post(title: String, content: String, seconds: Float, type: NotificationType, item: ItemStack? = null) {
        if (!NotificationsWidget.enabled) return
        if (silent) return
        val mc = Minecraft.getInstance()
        if (mc.player == null) return
        mc.execute {
            synchronized(list) { list += Notification(title, content, type, (seconds * 1600).toLong(), item, mc.window.guiScaledHeight / 2f + 41f) }
            when (type) {
                NotificationType.DISABLED -> Sounds.DISABLE.play()
                NotificationType.ENABLED -> Sounds.ENABLE.play()
                else -> {}
            }
        }
    }

    /** Once per frame in a world, before the HUD widgets. */
    fun render() {
        val window = Minecraft.getInstance().window
        var y0 = window.guiScaledHeight / 2f + 25
        if (list.size > 8) list.removeFirst()
        for (n in ArrayList(list)) {
            draw(n, y0 + list.size * 16, window.guiScaledWidth)
            y0 -= 16f
        }
        val now = System.currentTimeMillis()
        synchronized(list) { list.removeIf { now - it.start >= it.duration } }
    }

    private fun draw(n: Notification, targetY: Float, screenW: Int) {
        n.anim.update(if (System.currentTimeMillis() - n.start >= n.duration - 350) 0f else 1f)
        val v = n.anim.value
        if (v < 0.01f) return
        n.y += (targetY - n.y) / 8f
        drawModern(n, v, screenW)
    }

    /**
     * The plate in the new look, laid out as the one-line widgets are (the icon in the theme colour, the
     * divider, the text), popping in and out.
     */
    private fun drawModern(n: Notification, v: Float, screenW: Int) {
        val text = if (n.item == null) n.title + n.content else n.content
        val w = HudType.text.width(text) + 24.5f
        val x = screenW / 2f - w / 2f
        val pose = Canvas.pose
        pose.pushMatrix()
        pose.translate(x + w / 2f, n.y + 6.5f)
        pose.scale(v, v)
        pose.translate(-(x + w / 2f), -(n.y + 6.5f))
        Hud.plate(x, n.y, w, 13f, 3.5f, v)
        Canvas.quad(x + 17f, n.y + 2.25f, 0.5f, 8.5f, Colors.withAlpha(0x44FFFFFF, 0x44 / 255f * v))
        HudType.text.drawMiddle(text, x + 20.5f, n.y + 6.5f, Colors.withAlpha(Tok.T1, v))
        if (n.item == null) {
            n.type.vicon.draw(x + 4f, n.y + 1.5f, Colors.withAlpha(Tok.light(Tok.a1, 0.25f), v))
        } else {
            pose.translate(x + 3.8f, n.y + 1.3f)
            pose.scale(0.65f, 0.65f)
            Canvas.graphics?.item(n.item, 0, 0)
        }
        pose.popMatrix()
    }
}
