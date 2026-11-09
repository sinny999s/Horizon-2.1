package dev.horizon.client.module.cosmetic

import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.network.protocol.game.ClientboundSetTimePacket

/**
 * Environmental control over time of day and weather. Allows locking the world time to midnight,
 * sunset, or noon, and forcing clear weather to eliminate rain FPS drops and gloomy skies.
 */
class Ambience : Module("ambience", Categories.COSMETIC) {
    enum class TimeMode { OFF, DAY, NOON, SUNSET, NIGHT, MIDNIGHT, CUSTOM }
    enum class WeatherMode { VANILLA, CLEAR, RAIN, THUNDER }

    val timeMode = enum("time", TimeMode.MIDNIGHT)
    val customTime = int("custom-time", 18000, 0..24000) { timeMode.value == TimeMode.CUSTOM }
    val weather = enum("weather", WeatherMode.CLEAR)

    init {
        on<PlayerUpdateEvent> {
            val level = mc.level ?: return@on

            if (timeMode.value != TimeMode.OFF) {
                val t = when (timeMode.value) {
                    TimeMode.DAY -> 1000L
                    TimeMode.NOON -> 6000L
                    TimeMode.SUNSET -> 12000L
                    TimeMode.NIGHT -> 14000L
                    TimeMode.MIDNIGHT -> 18000L
                    TimeMode.CUSTOM -> customTime.value.toLong()
                    TimeMode.OFF -> return@on
                }
                level.setTimeFromServer(t)
            }

            when (weather.value) {
                WeatherMode.CLEAR -> {
                    level.setRainLevel(0f)
                    level.setThunderLevel(0f)
                }
                WeatherMode.RAIN -> {
                    level.setRainLevel(1f)
                    level.setThunderLevel(0f)
                }
                WeatherMode.THUNDER -> {
                    level.setRainLevel(1f)
                    level.setThunderLevel(1f)
                }
                WeatherMode.VANILLA -> {}
            }
        }

        on<ReceivePacketEvent> { e ->
            if (e.packet is ClientboundSetTimePacket && timeMode.value != TimeMode.OFF) {
                e.cancel()
            }
        }
    }
}
