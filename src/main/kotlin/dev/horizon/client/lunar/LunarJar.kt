package dev.horizon.client.lunar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.zip.Inflater

/**
 * Finds the current Lunar Client version and git commit the way Moonlight's `VersionFetcher` does.
 * Lunar's launcher API names the current `lunar.jar` for 1.8.9 (the same jar it names for every other Minecraft version);
 * its `lunarBuildData.txt` holds `lunarVersion` and `fullGitHash`. Only the zip's tail, central directory and that one entry are downloaded (HTTP ranges), not the whole
 * jar. Game-free, so the zip reading is tested on its own.
 */
object LunarJar {
    const val LAUNCH_API = "https://api.lunarclientprod.com/launcher/launch"
    const val USER_AGENT = "LunarClient/3.3.3"

    /** What [find] found. */
    data class Build(val version: String, val gitCommit: String, val gitBranch: String = "master")

    /** Reads bytes [start]..[end] (inclusive) of a file. */
    fun interface Ranges {
        fun read(start: Long, end: Long): ByteArray
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** Asks Lunar's launcher API, then the jar it names. Throws when something's missing. */
    fun fetch(): Build {
        val launch = HttpRequest.newBuilder(URI(LAUNCH_API)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT).header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(launchBody(LunarWire.os(), LunarWire.arch(), UUID.randomUUID(), System.getProperty("os.version", "6.1.0"))))
            .build()
        val res = http.send(launch, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "launch API returned ${res.statusCode()}" }
        val url = jarUrl(res.body()) ?: error("lunar.jar not in the launch API's answer")
        val head = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", USER_AGENT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        val size = head.headers().firstValueAsLong("content-length").orElse(-1)
        check(size > 0) { "lunar.jar's size unknown" }
        return find(size) { start, end ->
            val r = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", USER_AGENT)
                .header("Range", "bytes=$start-$end").build(), HttpResponse.BodyHandlers.ofByteArray())
            check(r.statusCode() == 206 || r.statusCode() == 200) { "range request returned ${r.statusCode()}" }
            r.body()
        } ?: error("lunarBuildData.txt not in lunar.jar")
    }

    /** The launcher API request, as Lunar's launcher 3.3.3 sends it for an offline 1.8.9 launch. */
    fun launchBody(os: String, arch: String, installation: UUID, osRelease: String): String = JsonObject().apply {
        addProperty("hwid", "0")
        addProperty("hwid_private", "0")
        addProperty("os", os)
        addProperty("arch", arch)
        addProperty("launcher_version", "3.3.3")
        addProperty("version", "1.8.9")
        addProperty("branch", "master")
        addProperty("launch_type", "OFFLINE")
        addProperty("installation_id", installation.toString())
        addProperty("os_release", osRelease)
        addProperty("module", "lunar")
    }.toString()

    /** The `lunar.jar` artifact's URL in the launcher API's answer. */
    fun jarUrl(json: String): String? {
        val artifacts = JsonParser.parseString(json).asJsonObject.getAsJsonObject("launchTypeData")?.getAsJsonArray("artifacts") ?: return null
        for (el in artifacts) {
            val art = el.asJsonObject
            if (art.has("name") && art.get("name").asString == "lunar.jar") return art.get("url").asString
        }
        return null
    }

    /** `lunarBuildData.txt`'s version and full git hash in a zip of [size] bytes, read through [ranges]. */
    fun find(size: Long, ranges: Ranges): Build? {
        val tailStart = maxOf(0L, size - 65536)
        val tail = ranges.read(tailStart, size - 1)
        val eocd = eocd(tail)
        if (eocd < 0) return null
        val e = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val cdSize = e.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = e.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val cd = ByteBuffer.wrap(ranges.read(cdOffset, cdOffset + cdSize - 1)).order(ByteOrder.LITTLE_ENDIAN)
        while (cd.remaining() > 46 && cd.getInt(cd.position()) == 0x02014b50) {
            val p = cd.position()
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            val compSize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            val localOffset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = ByteArray(nameLen).also { cd.position(p + 46); cd.get(it) }.toString(Charsets.UTF_8)
            if (name == "lunarBuildData.txt") {
                val props = entry(ranges, localOffset, compSize, method)
                val version = props.getProperty("lunarVersion") ?: return null
                val hash = props.getProperty("fullGitHash") ?: return null
                return Build(version, hash, props.getProperty("gitBranch") ?: "master")
            }
            cd.position(p + 46 + nameLen + extraLen + commentLen)
        }
        return null
    }

    /** The end-of-central-directory record's start in [tail], searching back from the end; -1 if missing. */
    fun eocd(tail: ByteArray): Int {
        for (i in tail.size - 22 downTo 0)
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) return i
        return -1
    }

    private fun entry(ranges: Ranges, localOffset: Long, compSize: Long, method: Int): Properties {
        val header = ByteBuffer.wrap(ranges.read(localOffset, localOffset + 29)).order(ByteOrder.LITTLE_ENDIAN)
        val start = localOffset + 30 + (header.getShort(26).toInt() and 0xFFFF) + (header.getShort(28).toInt() and 0xFFFF)
        var raw = if (compSize == 0L) ByteArray(0) else ranges.read(start, start + compSize - 1)
        if (method == 8) {
            val inf = Inflater(true)
            inf.setInput(raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            raw = out.toByteArray()
        }
        return Properties().apply { load(ByteArrayInputStream(raw)) }
    }
}
