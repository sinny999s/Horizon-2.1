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
 * texture.
 */
object UrlTextures {
    private val loaded = ConcurrentHashMap<String, DynamicTexture>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun get(url: String): GpuTextureView {
        loaded[url]?.let { return it.textureView }
        if (loading.add(url)) {
            Thread {
                try {
                    val image = URI(url).toURL().openStream().use { ImageIO.read(it) }
                    if (image != null) Minecraft.getInstance().execute { loaded[url] = Textures.upload("horizon url image", image) }
                } catch (_: Exception) {
                } finally {
                    loading.remove(url)
                }
            }.apply { isDaemon = true }.start()
        }
        return Minecraft.getInstance().textureManager.getTexture(MissingTextureAtlasSprite.getLocation()).textureView
    }
}
