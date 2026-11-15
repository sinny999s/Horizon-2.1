package dev.horizon.client.lunar

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * WebP pictures as AWT images, for Lunar's cosmetic textures (Lunar Client reads them with a native
 * libwebp; these are mostly lossless VP8L). TwelveMonkeys' reader is made directly rather than found through ImageIO's
 * plugin scan, which doesn't see jars nested in Horizon's. Game-free.
 */
object WebP {
    private val spi by lazy { WebPImageReaderSpi() }

    /**
     * The first picture in [bytes], with alpha; throws when it isn't a WebP. The reader fetches lossless image data 8
     * bytes at a time, so a picture with less data than that (Darkness Aura's 1×1 texture) ran off the end of the file:
     * 8 zero bytes after it give that fetch something to read, and are never decoded.
     */
    fun decode(bytes: ByteArray): BufferedImage {
        val reader = spi.createReaderInstance(null)
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size + 8))).use { input ->
                reader.input = input
                val img = reader.read(0)
                if (img.type == BufferedImage.TYPE_INT_ARGB) return img
                val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
                out.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
                return out
            }
        } finally {
            reader.dispose()
        }
    }
}
