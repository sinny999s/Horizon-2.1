package dev.horizon.client.render

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.image.BufferedImage

/** An AWT image as a GPU texture (straight alpha). */
object Textures {
    fun upload(label: String, image: BufferedImage): DynamicTexture {
        val native = NativeImage(image.width, image.height, false)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val a = argb ushr 24
                val r = argb shr 16 and 255
                val g = argb shr 8 and 255
                val b = argb and 255
                native.setPixelABGR(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        return DynamicTexture({ label }, native)
    }
}
