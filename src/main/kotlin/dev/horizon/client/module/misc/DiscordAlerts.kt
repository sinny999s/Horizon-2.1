package dev.horizon.client.module.misc

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.config.Friends
import dev.horizon.client.event.EntitySpawnEvent
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Sends rich Discord webhook alerts to your private Discord server for visual range (players
 * entering render distance), totem pops, low health, and base/stash finds. Webhook URL is left blank by default.
 */
class DiscordAlerts : Module("discord-alerts", Categories.MISC) {
    val webhook = string("webhook", "")
    val visualRange = bool("visual-range", true)
    val visualFriends = bool("visual-friends", false)
    val totemPops = bool("totem-pops", true)
    val lowHealth = bool("low-health", true)
    val healthThreshold = float("health-threshold", 8.0f, 2.0f..18.0f) { lowHealth.value }
    val cooldownSeconds = int("cooldown-s", 10, 1..60)

    private val executor = Executors.newSingleThreadExecutor()
    private val alertCooldowns = ConcurrentHashMap<String, Long>()
    private val seenPlayers = ConcurrentHashMap<String, Long>()
    private var healthAlertSent = false

    init {
        on<EntitySpawnEvent> { e ->
            if (!visualRange.value || webhook.value.isBlank()) return@on
            val player = mc.player ?: return@on
            val other = e.entity
            if (other !is Player || other === player) return@on

            val name = other.gameProfile.name
            val isFriend = Friends.isFriend(name)
            if (isFriend && !visualFriends.value) return@on

            val now = System.currentTimeMillis()
            val lastSeen = seenPlayers[name] ?: 0L
            if (now - lastSeen < cooldownSeconds.value * 1000L) return@on
            seenPlayers[name] = now

            val dist = player.distanceTo(other).roundToInt()
            val dim = mc.level?.dimension()?.identifier()?.path ?: "unknown"
            val coords = "${other.x.toInt()}, ${other.y.toInt()}, ${other.z.toInt()}"

            val title = if (isFriend) "Friend in Visual Range" else "WARNING: Enemy Player Spotted!"
            val color = if (isFriend) 0x2ECC71 else 0xE74C3C
            val desc = "**Player:** `$name`\n**Distance:** `${dist}m`\n**Coordinates:** `$coords`\n**Dimension:** `$dim`"

            sendWebhook(title, desc, color)
        }

        on<PlayerUpdateEvent> {
            val player = mc.player ?: return@on
            if (!lowHealth.value || webhook.value.isBlank()) return@on

            val hp = player.health + player.absorptionAmount
            if (hp <= healthThreshold.value && !healthAlertSent) {
                healthAlertSent = true
                val dim = mc.level?.dimension()?.identifier()?.path ?: "unknown"
                val coords = "${player.x.toInt()}, ${player.y.toInt()}, ${player.z.toInt()}"
                val totemsLeft = countTotems(player)
                sendWebhook(
                    "Low Health Alert!",
                    "**Health:** `${hp.roundToInt()} HP`\n**Totems in Inventory:** `$totemsLeft`\n**Location:** `$coords` ($dim)",
                    0xE67E22
                )
            } else if (hp > healthThreshold.value + 4f) {
                healthAlertSent = false
            }
        }

        on<ReceivePacketEvent> { e ->
            if (!totemPops.value || webhook.value.isBlank()) return@on
            val p = e.packet
            if (p !is ClientboundEntityEventPacket) return@on
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (p.eventId.toInt() == 35) { // 35 = Totem pop
                val entity = p.getEntity(level) ?: return@on
                if (entity === player) {
                    val totemsLeft = countTotems(player)
                    val coords = "${player.x.toInt()}, ${player.y.toInt()}, ${player.z.toInt()}"
                    sendWebhook(
                        "Totem Popped!",
                        "**Your totem popped!**\n**Remaining Totems:** `$totemsLeft`\n**Location:** `$coords`",
                        0xF1C40F
                    )
                }
            }
        }
    }

    private fun countTotems(player: Player): Int {
        var count = 0
        for (i in 0 until player.inventory.containerSize) {
            val item = player.inventory.getItem(i)
            if (item.`is`(Items.TOTEM_OF_UNDYING)) count += item.count
        }
        return count
    }

    private fun sendWebhook(title: String, description: String, color: Int) {
        val urlStr = webhook.value.trim()
        if (urlStr.isBlank() || !urlStr.startsWith("http")) return

        val now = System.currentTimeMillis()
        val last = alertCooldowns[title] ?: 0L
        if (now - last < 3000L) return
        alertCooldowns[title] = now

        executor.execute {
            try {
                val url = URI.create(urlStr).toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Horizon-Client")
                conn.doOutput = true

                val json = """
                {
                    "username": "Horizon 2.0 Alerts",
                    "avatar_url": "https://raw.githubusercontent.com/sinny999s/Horizon-2.1/main/src/main/resources/assets/horizon/icon.png",
                    "embeds": [
                        {
                            "title": "${escapeJson(title)}",
                            "description": "${escapeJson(description)}",
                            "color": $color,
                            "footer": { "text": "Horizon 2.0 • 6b6t Anarchy" },
                            "timestamp": "${Instant.now()}"
                        }
                    ]
                }
                """.trimIndent()

                OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                    os.write(json)
                    os.flush()
                }

                conn.responseCode // execute
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun escapeJson(str: String): String =
        str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    override fun onDisable() {
        alertCooldowns.clear()
        seenPlayers.clear()
        healthAlertSent = false
    }
}
