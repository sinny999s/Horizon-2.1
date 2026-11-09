package dev.horizon.client.module.world

import dev.horizon.client.event.BasicProjectionMatrixEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Animation
import dev.horizon.client.render.Easing
import dev.horizon.client.setting.Bind
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.screens.ChatScreen

/**
 * While [telephoto] is held the FOV is divided by [crop], while [ultraWide] is held by
 * [reversedCrop], easing there and back over 200 ms (OUT_QUINT).
 */
class Zoom : Module("zoom", Categories.WORLD) {
    val telephoto = bind("telephoto", Bind())
    val crop = float("crop", 8.0f, 1.0f..25.0f)
    val ultraWide = bind("ultra-wide", Bind())
    val reversedCrop = float("reversed-crop", 0.75f, 0.1f..1.0f)

    /**
     * It starts at 0, so the first frame after zoom is first enabled divides the FOV by 0
     * (a blank frame or two while it eases up to 1); kept on purpose.
     */
    private val scale = Animation(Easing.OUT_QUINT, 200)

    init {
        on<BasicProjectionMatrixEvent> { e ->
            scale.update(if (held(telephoto.value)) crop.value else if (held(ultraWide.value)) reversedCrop.value else 1.0f)
            if (!held(ultraWide.value) && !held(telephoto.value) && scale.value == 1.0f) return@on
            BasicProjectionMatrixEvent.fov = BasicProjectionMatrixEvent.fov / scale.value
            BasicProjectionMatrixEvent.ratio = mc.window.width.toFloat() / mc.window.height
            e.cancel()
        }
    }

    /** Whether [b] is held: mouse binds from the held-buttons list. */
    private fun held(b: Bind): Boolean {
        if (!RenderSystem.isOnRenderThread() || b.key == -1 || mc.gui.screen() is ChatScreen) return false
        return if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)
    }
}
