package dev.horizon.client.account

import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.horizon.client.render.Textures
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.image.BufferedImage
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * An account's face for the Accounts screen: the front of its skin's head with the hat layer over it, cut from the
 * skin itself (Mojang's texture server), drawn large with hard pixels. An account whose skin isn't known (the one the
 * game was started with, by another launcher) has it looked up by its id first. Nothing until it's in; one try a minute.
 */
object AccountHeads {
    private val loaded = ConcurrentHashMap<UUID, DynamicTexture>()
    private val loading = ConcurrentHashMap.newKeySet<UUID>()
    private val failed = ConcurrentHashMap<UUID, Long>()
    /** The skin each face was cut from: a changed skin is fetched again. */
    private val source = ConcurrentHashMap<UUID, String>()

    fun find(uuid: UUID, skin: String): GpuTextureView? {
        val have = loaded[uuid]
        if (have != null && (skin.isEmpty() || source[uuid] == skin)) return have.textureView
        val failedAt = failed[uuid]
        if ((failedAt == null || System.currentTimeMillis() - failedAt > 60_000L) && loading.add(uuid)) {
            Thread({
                try {
                    val url = skin.ifEmpty { lookUp(uuid) }
                    val face = url?.let(::secure)?.let { u -> URI(u).toURL().openStream().use { ImageIO.read(it) } }?.let(::face)
                    if (face != null) Minecraft.getInstance().execute {
                        loaded.put(uuid, Textures.upload("horizon account head", face))?.close()
                        source[uuid] = skin
                    } else failed[uuid] = System.currentTimeMillis()
                } catch (_: Exception) {
                    failed[uuid] = System.currentTimeMillis()
                } finally {
                    loading.remove(uuid)
                }
            }, "Horizon account head").apply { isDaemon = true }.start()
        }
        return have?.textureView
    }

    /** The skin of the account [uuid], from its public profile. */
    private fun lookUp(uuid: UUID): String? {
        val mc = Minecraft.getInstance()
        val session = mc.services().sessionService()
        val profile = session.fetchProfile(uuid, false)?.profile() ?: return null
        return session.getTextures(profile).skin()?.url
    }

    /** Skins are only ever fetched from Mojang's texture server, over https. */
    private fun secure(url: String): String? {
        val u = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (u.host != "textures.minecraft.net") return null
        return "https://textures.minecraft.net" + (u.rawPath ?: return null)
    }

    /** The 8 x 8 face at (8, 8) of a skin with its hat layer at (40, 8) over it, each pixel made [SCALE] wide. */
    private fun face(skin: BufferedImage): BufferedImage? {
        if (skin.width < 64 || skin.height < 32) return null
        val k = skin.width / 64
        val out = BufferedImage(8 * SCALE, 8 * SCALE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 8) {
            val base = skin.getRGB((8 + x) * k, (8 + y) * k) or 0xFF000000.toInt()
            val hat = skin.getRGB((40 + x) * k, (8 + y) * k)
            // (An old skin saved without transparency has a solid block where the hat would be.)
            val a = if (skin.colorModel.hasAlpha()) (hat ushr 24) / 255f else 0f
            val c = if (a <= 0f) base else {
                fun mix(shift: Int) = ((hat shr shift and 255) * a + (base shr shift and 255) * (1f - a)).toInt()
                0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
            for (dy in 0 until SCALE) for (dx in 0 until SCALE) out.setRGB(x * SCALE + dx, y * SCALE + dy, c)
        }
        return out
    }

    private const val SCALE = 8
}
