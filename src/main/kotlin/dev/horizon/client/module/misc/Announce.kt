package dev.horizon.client.module.misc

import com.mojang.blaze3d.Blaze3D
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.config.Storage
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Periodically sends announcements to chat or as commands at a configurable interval
 * (default 5.01s, slider range 1.0s..30.0s). Supports single text input or cycling lines from `announce.hzn`.
 */
class Announce : Module("announce", Categories.MISC) {
    enum class Mode { FILE, SINGLE }
    enum class Order { SEQUENTIAL, RANDOM }

    val mode = enum("mode", Mode.FILE)
    val order = enum("order", Order.SEQUENTIAL) { mode.value == Mode.FILE }
    val openFile = action("open-file", visible = { mode.value == Mode.FILE }) {
        ensureFile()
        Blaze3D.openPath(file.toPath())
    }
    val message = string("message", "Horizon 2.0 on top") { mode.value == Mode.SINGLE }
    val delay = float("delay", 5.01f, 1.0f..30.0f)
    val antiSpam = bool("anti-spam", false)
    val global = bool("global", false)
    val sendNow = action("send-now") {
        send()
    }

    private val timer = MsTimer()
    private var lines: List<String> = emptyList()
    private var loadedAt = 0L
    private var currentIndex = 0

    val file: File
        get() = Storage.root.also { Files.createDirectories(it) }.resolve("announce.hzn").toFile()

    init {
        on<PlayerUpdateEvent> { tick() }
    }

    override fun onEnable() {
        timer.reset()
        currentIndex = 0
        if (mode.value == Mode.FILE) {
            lines = load()
        }
    }

    private fun tick() {
        val p = mc.player ?: return
        if (mc.connection == null) return

        if (mode.value == Mode.FILE && p.tickCount % 60 == 0 && file.exists() && file.lastModified() - loadedAt > 0) {
            lines = load()
        }

        if (timer.passed((delay.value * 1000).toLong())) {
            timer.reset()
            send()
        }
    }

    fun send() {
        val connection = mc.connection ?: return
        var msg = when (mode.value) {
            Mode.SINGLE -> message.value.trim()
            Mode.FILE -> nextLine() ?: return
        }
        if (msg.isEmpty()) return

        if (antiSpam.value) {
            msg += tag()
        }

        if (msg.startsWith("/")) {
            connection.sendCommand(msg.removeRange(0, 1))
        } else if (global.value && !msg.startsWith("!")) {
            connection.sendChat("!$msg")
        } else {
            connection.sendChat(msg)
        }
    }

    private fun nextLine(): String? {
        if (lines.isEmpty() || (file.exists() && file.lastModified() - loadedAt > 0)) {
            lines = load()
        }
        if (lines.isEmpty()) return null

        return when (order.value) {
            Order.SEQUENTIAL -> {
                val line = lines[currentIndex % lines.size]
                currentIndex = (currentIndex + 1) % lines.size
                line
            }
            Order.RANDOM -> lines.random()
        }
    }

    private fun load(): List<String> {
        ensureFile()
        loadedAt = file.lastModified()
        return if (file.exists()) {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
        } else {
            emptyList()
        }
    }

    fun ensureFile() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_ANNOUNCEMENTS)
        } else if (file.readText().trim() == OLD_DEFAULT.trim()) {
            // Still the old default, untouched: the new one (the repository moved to Horizon-2.1). Edited files stay.
            file.writeText(DEFAULT_ANNOUNCEMENTS)
        }
    }

    private fun tag() = " [" + (Random.nextInt(26) + 97).toChar() + Random.nextInt(10) + (Random.nextInt(26) + 97).toChar() + "]"

    companion object {
        val DEFAULT_ANNOUNCEMENTS = """
# Horizon 2.0 Announcements (one per line, # for comments)
Horizon 2.0 is out now! Next-gen Minecraft 26.3 utility client: github.com/sinny999s/Horizon-2.1
Upgrade to Horizon 2.0 - Fast AutoCrystal, Elytra Autopilot & Modern Glass UI: github.com/sinny999s/Horizon-2.1
Dominate PvP with Horizon 2.0 for 26.3 | Free & Open Source: github.com/sinny999s/Horizon-2.1
Hunt stashes and bases with Horizon 2.0 (Hunt Map & BaseFinder): github.com/sinny999s/Horizon-2.1
        """.trimIndent()

        /** The default before the repository moved (links to Horizon-2.0), to replace in files nobody edited. */
        private val OLD_DEFAULT = """
# Horizon 2.0 Announcements (one per line, # for comments)
Horizon 2.0 is out now! Next-gen Minecraft 26.3 utility client: github.com/sinny999s/Horizon-2.0
Upgrade to Horizon 2.0 - Fast AutoCrystal, Elytra Autopilot & Modern Glass UI: github.com/sinny999s/Horizon-2.0
Dominate PvP with Horizon 2.0 for 26.3 | Free & Open Source: github.com/sinny999s/Horizon-2.0
Hunt stashes and bases with Horizon 2.0 (Hunt Map & BaseFinder): github.com/sinny999s/Horizon-2.0
        """.trimIndent()
    }
}
