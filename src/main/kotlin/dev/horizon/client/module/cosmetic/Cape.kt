package dev.horizon.client.module.cosmetic

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import dev.horizon.client.event.SkinEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.Textures
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Wears any of Minecraft's own capes (Java's official ones, Mojang's and the event capes included), and
 * with [elytra] the same art on your elytra (or vanilla's elytra without it). Only you see it: other players get your
 * real cape, if any, from the server. The pictures come from Mojang's texture server the first time each is worn and
 * are kept under Storage's `capes` folder; until one is there you keep the cape you have.
 */
class Cape : Module("cape", Categories.COSMETIC) {
    /** Each cape, by its name on the Minecraft Wiki, and its picture on textures.minecraft.net. */
    enum class Style(val hash: String) {
        PAN("28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"),
        MIGRATOR("2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"),
        VANILLA("f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"),
        COMMON("5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"),
        HERO("bb384a1079b9a6f2811520c7991b6e8150d02e5a96457e44509e30822f72f38e"),
        TWISTED("24aafc451aa2cc34ddc7265211678585c0ef4da4d32edb75ecec1bd8b5408381"),
        CLASSIC_MOJANG("8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761"),
        MOJANG("5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151"),
        MOJANG_STUDIOS("9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"),
        MINECON_2011("953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"),
        MINECON_2012("a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"),
        MINECON_2013("153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"),
        MINECON_2015("b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"),
        MINECON_2016("e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"),
        MINECRAFT_EXPERIENCE("7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434"),
        MOONLIGHT_TRAIL("fe8a02dfe9e390e44ff33d69feef9d3943f76d3901015bbd50f0b67722d288bd"),
        CRAFTER("479eacefa3cdd7aca94207f36c0dd449653ddf259daf40544a5866baf05eee22"),
        FOUNDERS("99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"),
        CHERRY_BLOSSOM("afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"),
        FOLLOWERS("569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"),
        PURPLE_HEART("cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"),
        ANNIVERSARY_15TH("cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"),
        MCC_15TH_YEAR("56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df"),
        MOJANG_OFFICE("5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"),
        HOME("1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"),
        MENACE("dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"),
        YEARN("308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89"),
        COPPER("5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"),
        ZOMBIE_HORSE("a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b"),
        BUILDER("2c579968c64c1719740fd8c2a451461879b238002574fce48f7d1a7c36a1c7d4"),
        AURORA("7c418dfbd37412a55e9f1425c9807a591ad10cf71b1edb576a587be9114c277f"),
        BACON("fd14214cd8073059e93d9c626260f5df85e5a959181537119df56cadaf5002cc"),
        MILLIONTH_CUSTOMER("70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf"),
        DB("bcfbe84c6542a4a5c213c1cacf8979b5e913dcb4ad783a8b80e3c4a7d5c8bdac"),
        SNOWMAN("23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe"),
        CHEAPSH0T("ca29f5dd9e94fb1748203b92e36b66fda80750c87ebc18d6eafdb0e28cc1d05f"),
        SPADE("2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8"),
        PRISMARINE("d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac"),
        TURTLE("5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95"),
        BIRTHDAY("2056f2eebd759cce93460907186ef44e9192954ae12b227d817eb4b55627a7fc"),
        VALENTINE("e578ef995fabcf0a94768f9651ac3aaba30c59ef85d2438e9b3e0cc1d810652b"),
        OXEYE("7706b5f5fc90329691e59277dcc66ba20572219fa8e5da472afd5235fad12cc8"),
        BLUEPRINT("fdcf48f01ec480d1d7cbec27f7ddce48c9da2be6724641109444dae58d4cd013"),
        SCROLLS_CHAMPION("3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245"),
        COBALT("ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"),
        TRANSLATOR("1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e"),
        CHINESE_TRANSLATOR("2262fb1d24912209490586ecae98aca8500df3eff91f2a07da37ee524e7e3cb6"),
        MODERATOR("ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551"),
        REALMS_MAPMAKER("17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56"),
        TEST("7a93b1867eb599f2b76e6e1c30a0ddb530e6f4c7bce6515d1ba72b206df30e39"),
    }

    val style = enum("style", Style.MIGRATOR)
    val elytra = bool("elytra", true)
    val waveyCapes = bool("wavey-capes", true)

    private val textures = HashMap<Style, ClientAsset.ResourceTexture>()

    /** Pictures fetched and waiting for the render thread to upload them. */
    private val fetched = ConcurrentHashMap<Style, BufferedImage>()
    private val fetching = ConcurrentHashMap.newKeySet<Style>()

    /** When a cape whose fetch failed may be tried again. */
    private val retryAt = ConcurrentHashMap<Style, Long>()
    private var lastIn: PlayerSkin? = null
    private var lastOut: PlayerSkin? = null

    init {
        on<SkinEvent> { e ->
            val skin = e.skin ?: return@on
            if (e.player?.uuid != mc.player?.uuid) return@on
            val cape = texture(style.value) ?: return@on
            val wings = if (elytra.value) cape else skin.elytra() ?: VANILLA_ELYTRA
            val out = lastOut
            e.skin = if (lastIn == skin && out != null && out.cape() == cape && out.elytra() == wings) out
            else PlayerSkin(skin.body(), cape, wings, skin.model(), skin.secure()).also { lastIn = skin; lastOut = it }
        }
    }

    /** [style]'s picture once it's uploaded; null while it's being fetched. */
    private fun texture(style: Style): ClientAsset.ResourceTexture? {
        textures[style]?.let { return it }
        val image = fetched.remove(style) ?: run {
            val file = Storage.dir("capes").resolve("${style.hash}.png")
            if (Files.exists(file)) {
                try {
                    ByteArrayInputStream(Files.readAllBytes(file)).use { ImageIO.read(it) }
                } catch (_: Throwable) { null }
            } else {
                fetch(style)
                null
            }
        }
        if (image == null) return null
        val id = Identifier.fromNamespaceAndPath(HorizonClient.ID, "textures/capes/${style.name.lowercase()}.png")
        mc.textureManager.register(id, Textures.upload("cape $style", image))
        return ClientAsset.ResourceTexture(id, id).also { textures[style] = it }
    }

    /** Reads [style]'s picture from the folder, or from Mojang's server into the folder, off the render thread. */
    private fun fetch(style: Style) {
        if (System.currentTimeMillis() < (retryAt[style] ?: 0L) || !fetching.add(style)) return
        Thread({
            try {
                val file = Storage.dir("capes").resolve("${style.hash}.png")
                val saved = Files.exists(file)
                val bytes = if (saved) Files.readAllBytes(file)
                else URI("$TEXTURE_SERVER${style.hash}").toURL().openStream().use { it.readAllBytes() }
                val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: error("not a picture")
                if (!saved) Files.write(file, bytes)
                fetched[style] = image
            } catch (t: Throwable) {
                retryAt[style] = System.currentTimeMillis() + RETRY_MS
                HorizonClient.LOG.warn("Couldn't fetch the {} cape: {}", style, t.toString())
                fetching.remove(style)
            }
        }, "horizon cape").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TEXTURE_SERVER = "https://textures.minecraft.net/texture/"
        const val RETRY_MS = 30_000L
        val VANILLA_ELYTRA = ClientAsset.ResourceTexture(
            Identifier.withDefaultNamespace("elytra"), Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png"))
    }
}
