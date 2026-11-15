package dev.horizon.client.lunar

import kotlin.test.Test
import kotlin.test.assertEquals

/** [WebP] on pictures Lunar's CDN serves. */
class WebPTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /**
     * Darkness Aura's texture: a 1×1 lossless picture whose image data is 4 bytes, so a reader that fetches bits ahead
     * runs off the end. libwebp reads it as one opaque black pixel.
     */
    @Test
    fun `a 1x1 lossless picture with a few bytes of data`() {
        val img = WebP.decode(bytes(82, 73, 70, 70, 22, 0, 0, 0, 87, 69, 66, 80, 86, 80, 56, 76, 9, 0, 0, 0,
            47, 0, 0, 0, 0, -120, -120, -2, 7, 0))
        assertEquals(1, img.width)
        assertEquals(1, img.height)
        assertEquals(0xFF000000.toInt(), img.getRGB(0, 0))
    }
}
