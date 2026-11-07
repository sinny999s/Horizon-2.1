package dev.horizon.client.updater

import dev.horizon.client.HorizonClient
import dev.horizon.client.hud.Notifications
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.config.Storage
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Native background auto-updater: checks GitHub releases for updates matching the current Minecraft version,
 * downloads them to `horizon/updates`, and stages a Windows-safe swap on game shutdown.
 */
object AutoUpdater {
    private const val REPO = "sinny999s/Horizon-2.1"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases"

    val currentVersion: String = FabricLoader.getInstance().getModContainer(HorizonClient.ID)
        .map { it.metadata.version.friendlyString }.orElse("2.0.0")

    val currentMcVersion: String = FabricLoader.getInstance().getModContainer("minecraft")
        .map { it.metadata.version.friendlyString }.orElse("26.3")

    var status: String = ""
        private set

    var updatePending: Boolean = false
        private set

    private var checking = false

    fun checkOnStartup() {
        cleanupOldJars()
        if (!Storage.shared.autoUpdate) return
        thread(name = "Horizon-AutoUpdater", isDaemon = true) {
            // Wait 5 seconds after launch to ensure game settles before network request
            try {
                Thread.sleep(5000)
            } catch (ignored: InterruptedException) {
                return@thread
            }
            check(manual = false)
        }
    }

    fun check(manual: Boolean = false) {
        if (checking) return
        checking = true
        status = "Checking for updates..."
        thread(name = "Horizon-Updater-Check", isDaemon = true) {
            try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("User-Agent", "Horizon-Client/$currentVersion")
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    status = "Check failed (HTTP ${response.statusCode()})"
                    if (manual) Notifications.post("Auto-Updater", status, 3f, NotificationType.WARNING)
                    return@thread
                }

                val releases = Storage.json.parseToJsonElement(response.body()).jsonArray
                if (releases.isEmpty()) {
                    status = "No releases found"
                    if (manual) Notifications.post("Auto-Updater", status, 3f, NotificationType.INFO)
                    return@thread
                }

                // Filter for a release that has an asset specifically for our Minecraft version (e.g. horizon-26.3-*.jar)
                val targetPrefix = "horizon-$currentMcVersion-"
                var candidateRelease: kotlinx.serialization.json.JsonObject? = null
                var candidateAsset: kotlinx.serialization.json.JsonObject? = null
                var releaseTag = ""

                for (relElem in releases) {
                    val rel = relElem.jsonObject
                    val tag = rel["tag_name"]?.jsonPrimitive?.content ?: continue
                    val assets = rel["assets"]?.jsonArray ?: continue
                    val match = assets.firstOrNull { a ->
                        val name = a.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        name.startsWith(targetPrefix) && name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-dev.jar")
                    }?.jsonObject

                    if (match != null) {
                        candidateRelease = rel
                        candidateAsset = match
                        releaseTag = tag
                        break
                    }
                }

                if (candidateRelease == null || candidateAsset == null) {
                    status = "Horizon is up to date (no newer $currentMcVersion release)"
                    if (manual) Notifications.post("Auto-Updater", "Up to date for Minecraft $currentMcVersion", 3f, NotificationType.INFO)
                    return@thread
                }

                if (!isNewer(releaseTag, currentVersion)) {
                    status = "Horizon v$currentVersion is up to date"
                    if (manual) Notifications.post("Auto-Updater", "Horizon v$currentVersion is up to date", 3f, NotificationType.INFO)
                    return@thread
                }

                // A newer version matching our MC version is available!
                val downloadUrl = candidateAsset["browser_download_url"]?.jsonPrimitive?.content ?: return@thread
                val assetName = candidateAsset["name"]?.jsonPrimitive?.content ?: return@thread

                status = "Downloading $releaseTag..."
                if (manual) Notifications.post("Auto-Updater", "Downloading Horizon $releaseTag...", 3f, NotificationType.INFO)

                val updatesDir = Storage.dir("updates")
                val stagedJar = updatesDir.resolve(assetName)

                val dlReq = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", "Horizon-Client/$currentVersion")
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build()

                val dlRes = client.send(dlReq, HttpResponse.BodyHandlers.ofFile(stagedJar, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                if (dlRes.statusCode() != 200) {
                    status = "Download failed (HTTP ${dlRes.statusCode()})"
                    Notifications.post("Auto-Updater", status, 3f, NotificationType.ERROR)
                    return@thread
                }

                // Update downloaded. Schedule swap on shutdown.
                val modContainer = FabricLoader.getInstance().getModContainer(HorizonClient.ID).orElse(null)
                val currentJar = modContainer?.origin?.paths?.firstOrNull()

                if (currentJar != null && currentJar.toString().endsWith(".jar")) {
                    scheduleSwap(currentJar, stagedJar, currentJar.parent.resolve(assetName))
                    updatePending = true
                    status = "Updated to $releaseTag (restart to apply)"
                    Notifications.post("Horizon Update", "Updated to $releaseTag! Restart game to apply.", 7f, NotificationType.SUCCESS)
                } else {
                    status = "Downloaded $assetName to updates/"
                    Notifications.post("Auto-Updater", "Downloaded $assetName (dev run)", 4f, NotificationType.INFO)
                }
            } catch (e: Exception) {
                status = "Updater error: ${e.message}"
                HorizonClient.LOG.warn("Auto-updater encountered an error", e)
                if (manual) Notifications.post("Auto-Updater", "Update check failed", 3f, NotificationType.WARNING)
            } finally {
                checking = false
            }
        }
    }

    private fun scheduleSwap(oldJar: Path, newJar: Path, destJar: Path) {
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val oldStr = oldJar.toAbsolutePath().toString()
                val newStr = newJar.toAbsolutePath().toString()
                val destStr = destJar.toAbsolutePath().toString()

                if (isWindows) {
                    // Give java.exe 2 seconds to release file lock, then delete old and move new
                    val cmd = "ping 127.0.0.1 -n 3 >nul & del /f /q \"$oldStr\" & move /y \"$newStr\" \"$destStr\""
                    ProcessBuilder("cmd.exe", "/c", cmd).start()
                } else {
                    val cmd = "sleep 2 && rm -f \"$oldStr\" && mv \"$newStr\" \"$destStr\""
                    ProcessBuilder("sh", "-c", cmd).start()
                }
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Failed to execute update swap script", e)
            }
        })
    }

    private fun cleanupOldJars() {
        try {
            val container = FabricLoader.getInstance().getModContainer(HorizonClient.ID).orElse(null) ?: return
            val currentJar = container.origin.paths.firstOrNull() ?: return
            if (!currentJar.toString().endsWith(".jar")) return
            val modsDir = currentJar.parent ?: return

            java.nio.file.Files.list(modsDir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    name.startsWith("horizon-") && name.endsWith(".jar") && p != currentJar
                }.forEach { old ->
                    try {
                        java.nio.file.Files.deleteIfExists(old)
                        HorizonClient.LOG.info("AutoUpdater: Cleaned up old jar {}", old.fileName)
                    } catch (ignored: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Failed cleaning up old jars", e)
        }
    }

    fun isNewer(latestTag: String, current: String): Boolean {
        val v1 = latestTag.trimStart('v').split('.', '-').mapNotNull { it.toIntOrNull() }
        val v2 = current.trimStart('v').split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(v1.size, v2.size)) {
            val p1 = v1.getOrElse(i) { 0 }
            val p2 = v2.getOrElse(i) { 0 }
            if (p1 > p2) return true
            if (p1 < p2) return false
        }
        return false
    }
}
