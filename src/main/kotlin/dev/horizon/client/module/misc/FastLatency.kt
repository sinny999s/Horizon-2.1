package dev.horizon.client.module.misc

import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.PostTasksEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket

/**
 * Measures the round trip with a tab-complete request ("/w ", id 1337) and warns on
 * spikes; while it's on, `PlayerState.ping` is this value.
 */
class FastLatency : Module("fast-latency", Categories.MISC) {
    enum class SpikeDetector { OFF, NOTIFICATION, CHAT, BOTH }

    val delay = int("delay", 80, 0..1000)
    val spikeDetector = enum("spike-detector", SpikeDetector.CHAT)
    val messageDelay = int("message-delay", 500, 0..10000)

    /** The last round trip in ms (0 until the first reply). */
    @Volatile
    var latency = 0
        private set

    @Volatile
    private var sentAt = 0L

    private val samples = ArrayDeque<Int>(20)

    /** Reset on a probe, set to "5 s passed" on its reply; a new probe waits for it. */
    @Volatile
    private var awaitStart = System.currentTimeMillis()

    private val probeTimer = MsTimer()
    private val spikeMsgTimer = MsTimer()

    init {
        on<PostTasksEvent> {
            if (mc.player == null || mc.level == null) return@on
            if (System.currentTimeMillis() - awaitStart >= 5000 && probeTimer.passedAndReset(delay.value.toLong())) {
                Packets.send(ServerboundCommandSuggestionPacket(1337, "w "))
                sentAt = System.currentTimeMillis()
                awaitStart = System.currentTimeMillis()
            }
        }

        on<ReceivePacketEvent> { e ->
            val pk = e.packet
            if (pk !is ClientboundCommandSuggestionsPacket || pk.id() != 1337) return@on
            latency = (System.currentTimeMillis() - sentAt).coerceIn(0L, 1000L).toInt()
            synchronized(samples) {
                if (samples.size >= 20) samples.removeFirst()
                samples.addLast(latency)
                if (latency > samples.average().toFloat() * 1.15f && samples.size >= 20 &&
                    spikeMsgTimer.passedAndReset(messageDelay.value.toLong())) {
                    val msg = "ping spike detected! ${samples.average().toInt()}ms -> ${latency}ms"
                    val mode = spikeDetector.value
                    if (mode == SpikeDetector.BOTH || mode == SpikeDetector.NOTIFICATION) Notifications.post(name, msg, 2f, NotificationType.WARNING)
                    if (mode == SpikeDetector.BOTH || mode == SpikeDetector.CHAT) moduleMessage(name.trim(), msg)
                }
            }
            awaitStart = System.currentTimeMillis() - 5000
        }
    }
}
