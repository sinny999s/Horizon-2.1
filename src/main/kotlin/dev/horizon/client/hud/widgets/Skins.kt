package dev.horizon.client.hud.widgets

import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.HorizonClient
import dev.horizon.client.render.Canvas
import dev.horizon.client.render.Colors
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Skin heads and the sprite atlas (`horizon:textures/atlas.png`) for the HUD widgets. */
object Skins {
    private val ATLAS: Identifier = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/atlas.png")

    /** Atlas cells (u0, v0, u1, v1). */
    val FIREFLY_ALT = floatArrayOf(0.3906f, 0.25f, 0.4531f, 0.3125f)
    val DROP = floatArrayOf(0.8906f, 0.125f, 0.9531f, 0.1875f)

    fun texture(id: Identifier): GpuTextureView = Minecraft.getInstance().textureManager.getTexture(id).textureView

    fun skin(p: AbstractClientPlayer): GpuTextureView = texture(p.skin.body().texturePath())

    fun atlas(): GpuTextureView = texture(ATLAS)

    /** The face (skin 8..16) at ([fx], [fy]) and the hat layer (40..48) at ([hx], [hy]). */
    fun head(p: AbstractClientPlayer, fx: Float, fy: Float, fs: Float, hx: Float, hy: Float, hs: Float, color: Int = -1) {
        val t = skin(p)
        Canvas.texture(t, fx, fy, fs, fs, color, color, color, color, 0.125f, 0.125f, 0.25f, 0.25f)
        Canvas.texture(t, hx, hy, hs, hs, color, color, color, color, 0.625f, 0.125f, 0.75f, 0.25f)
    }

    /**
     * The face with the hat layer over it at the same size, in a square rounded by [r] (a CSS
     * border-radius on a skin head), drawn as one-pixel rows; [alpha] fades it.
     */
    fun roundedHead(p: AbstractClientPlayer, x: Float, y: Float, size: Float, r: Float, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        val t = skin(p)
        val c = Colors.withAlpha(-1, alpha.coerceAtMost(1f))
        val px = 1f / Minecraft.getInstance().window.guiScale
        val n = ceil(size / px).toInt()
        for (i in 0 until n) {
            val y0 = i * px
            val y1 = min(size, y0 + px)
            val mid = (y0 + y1) / 2f
            val d = if (mid < r) r - mid else if (mid > size - r) mid - (size - r) else 0f
            val inset = if (d > 0f) r - sqrt(max(0f, r * r - d * d)) else 0f
            val u0 = inset / size / 8f
            val u1 = (1f - inset / size) / 8f
            val v0 = 0.125f + y0 / size / 8f
            val v1 = 0.125f + y1 / size / 8f
            Canvas.texture(t, x + inset, y + y0, size - 2f * inset, y1 - y0, c, c, c, c, 0.125f + u0, v0, 0.125f + u1, v1)
            Canvas.texture(t, x + inset, y + y0, size - 2f * inset, y1 - y0, c, c, c, c, 0.625f + u0, v0, 0.625f + u1, v1)
        }
    }

    /** An additive atlas sprite with its top-left at ([x], [y]). */
    fun sprite(cell: FloatArray, x: Float, y: Float, w: Float, h: Float, color: Int) {
        Canvas.texture(atlas(), x, y, w, h, color, color, color, color, cell[0], cell[1], cell[2], cell[3], additive = true)
    }
}
