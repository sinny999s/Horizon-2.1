package dev.horizon.client.module.misc

import com.mojang.blaze3d.Blaze3D
import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.config.Storage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * Every [delay] seconds a random line of `spammer.txt` (in the client folder; re-read every 60 ticks when it changed) to
 * global ("!" first), local chat or a whisper to a random tab-list player; a line starting with "/" is sent as a command.
 * With nothing to send it turns itself off.
 */
class Spammer : Module("spammer", Categories.MISC) {
    enum class Mode { GLOBAL, LOCAL, WHISPERS }

    val openFile = action("open-file") {
        if (!file.exists()) file.createNewFile()
        Blaze3D.openPath(file.toPath())
    }
    val mode = enum("mode", Mode.GLOBAL)
    val prefix = string("prefix", "msg") { mode.value == Mode.WHISPERS }
    val antiSpam = bool("anti-spam", false)
    val delay = float("delay", 5.0f, 0.0f..30.0f)

    private val timer = MsTimer()
    private var lines: List<String> = emptyList()
    private var loadedAt = 0L

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    override fun onEnable() {
        lines = load()
    }

    private fun tick() {
        val p = mc.player ?: return
        if (p.tickCount % 60 == 0 && file.exists() && file.lastModified() - loadedAt > 0) lines = load()
        if (timer.passed((delay.value * 1000).toInt().toLong()) && lines.isNotEmpty()) {
            val msg = lines.random() + if (antiSpam.value) " " + tag() else ""
            val connection = mc.connection!!
            if (msg.startsWith("/")) {
                connection.sendCommand(msg.removeRange(0, 1))
            } else {
                when (mode.value) {
                    Mode.GLOBAL -> connection.sendChat("!$msg")
                    Mode.LOCAL -> connection.sendChat(msg)
                    Mode.WHISPERS -> connection.sendCommand(prefix.value + " " + randomPlayer() + " " + msg)
                }
            }
            timer.reset()
            return
        }
        if (lines.isEmpty()) {
            disable()
            Misc2Util.chat(this, "The spammer file is empty!")
        }
    }

    /** The file's trimmed non-blank lines; a file that had to be created gives none. */
    private fun load(): List<String> {
        if (file.createNewFile()) return emptyList()
        loadedAt = file.lastModified()
        if (!file.exists()) file.createNewFile()
        return file.readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    /** "[a1b]", against anti-spam filters. */
    private fun tag() = "[" + (Random.nextInt(26) + 97).toChar() + Random.nextInt(10) + (Random.nextInt(26) + 97).toChar() + "]"

    /** A random tab-list name that is a valid username, or "null" when there's none. */
    private fun randomPlayer(): String? = mc.connection!!.onlinePlayers.asSequence().map { it.profile.name }
        .filter { Pattern.matches("^\\w{3,16}$", it) }.toList().randomOrNull()

    private companion object {
        val file: File get() = Storage.root.also { Files.createDirectories(it) }.resolve("spammer.txt").toFile()
    }
}
