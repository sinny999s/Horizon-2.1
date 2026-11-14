package dev.horizon.client.gui

import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.render.Textures
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * URL images: loaded once per URL on a background thread; until then (and when it fails) the missing
 * texture from [get], nothing from [find]. A download that failed is tried again after a minute, not every frame.
 */
object UrlTextures {
    private val loaded = ConcurrentHashMap<String, DynamicTexture>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    /** When a download last failed: it isn't tried again for [RETRY_MS] (not every frame). */
    private val failed = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 60_000L

    /** [url]'s picture once it is in, else null (it starts loading): for a caller with something of its own to show meanwhile. */
    fun find(url: String): GpuTextureView? {
        loaded[url]?.let { return it.textureView }
        get(url)
        return null
    }

    fun get(url: String): GpuTextureView {
        loaded[url]?.let { return it.textureView }
        val failedAt = failed[url]
        if ((failedAt == null || System.currentTimeMillis() - failedAt > RETRY_MS) && loading.add(url)) {
            Thread {
                try {
                    val image = URI(url).toURL().openStream().use { ImageIO.read(it) }
                    if (image != null) Minecraft.getInstance().execute { loaded[url] = Textures.upload("horizon url image", image) }
                    else failed[url] = System.currentTimeMillis()
                } catch (_: Exception) {
                    failed[url] = System.currentTimeMillis()
                } finally {
                    loading.remove(url)
                }
            }.apply { isDaemon = true }.start()
        }
        return Minecraft.getInstance().textureManager.getTexture(MissingTextureAtlasSprite.getLocation()).textureView
    }
}
