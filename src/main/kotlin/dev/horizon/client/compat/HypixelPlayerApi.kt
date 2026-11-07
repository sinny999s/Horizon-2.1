package dev.horizon.client.compat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Not in the original: a player's stats from Hypixel's public API (`GET https://api.hypixel.net/v2/player?uuid=...` with an
 * `API-Key` header; a key is free from developer.hypixel.net). Only what the Hypixel Stats widget shows: the network
 * level and Bedwars. Game-free, so it's tested on its own; the widget does the asking and keeping.
 */
object HypixelPlayerApi {
    const val URL = "https://api.hypixel.net/v2/player"

    @Serializable
    data class Stats(
        val name: String,
        val networkLevel: Double,
        val bedwarsStars: Int,
        val kills: Int,
        val deaths: Int,
        val finalKills: Int,
        val finalDeaths: Int,
        val wins: Int,
        val losses: Int,
        val bedsBroken: Int,
        val bedsLost: Int,
        /** Null when the player hides it (Hypixel's API setting). */
        val winstreak: Int?,
        val fetchedAt: Long,
    ) {
        val fkdr get() = ratio(finalKills, finalDeaths)
        val kdr get() = ratio(kills, deaths)
        val wlr get() = ratio(wins, losses)
        val bblr get() = ratio(bedsBroken, bedsLost)
    }

    /** What an answer said: stats, or why not. */
    sealed interface Result {
        data class Ok(val stats: Stats) : Result
        data class Failed(val reason: String) : Result
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /** Asks Hypixel for [uuid]'s stats with [key] (blocking: call it off the game thread). */
    fun fetch(key: String, uuid: UUID): Result {
        val req = HttpRequest.newBuilder(URI("$URL?uuid=$uuid")).timeout(Duration.ofSeconds(10))
            .header("API-Key", key).header("User-Agent", "Horizon").GET().build()
        val res = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return Result.Failed("couldn't reach Hypixel (${e.message ?: e.javaClass.simpleName})")
        }
        return parse(res.statusCode(), res.body(), System.currentTimeMillis())
    }

    /** An answer from the API: its HTTP status and body. */
    fun parse(status: Int, body: String, now: Long): Result {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return Result.Failed("unreadable answer (HTTP $status)")
        }
        if (status == 403) return Result.Failed("API key refused: ^hypixel key <key>")
        if (status == 429) return Result.Failed("too many requests; trying again later")
        if (status != 200 || root.get("success")?.asBoolean != true)
            return Result.Failed(root.get("cause")?.takeIf { it.isJsonPrimitive }?.asString ?: "HTTP $status")
        val player = root.get("player")?.takeIf { it.isJsonObject }?.asJsonObject ?: return Result.Failed("this account has never joined Hypixel")
        val bw = player.obj("stats")?.obj("Bedwars")
        val exp = player.get("networkExp")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        return Result.Ok(Stats(
            name = player.get("displayname")?.takeIf { it.isJsonPrimitive }?.asString ?: "?",
            networkLevel = networkLevel(exp),
            bedwarsStars = player.obj("achievements")?.int("bedwars_level") ?: 0,
            kills = bw?.int("kills_bedwars") ?: 0,
            deaths = bw?.int("deaths_bedwars") ?: 0,
            finalKills = bw?.int("final_kills_bedwars") ?: 0,
            finalDeaths = bw?.int("final_deaths_bedwars") ?: 0,
            wins = bw?.int("wins_bedwars") ?: 0,
            losses = bw?.int("losses_bedwars") ?: 0,
            bedsBroken = bw?.int("beds_broken_bedwars") ?: 0,
            bedsLost = bw?.int("beds_lost_bedwars") ?: 0,
            winstreak = bw?.int("winstreak"),
            fetchedAt = now,
        ))
    }

    /** Hypixel's network level for [exp] network XP, with the fraction to the next level (its `ILeveling.getExactLevel`). */
    fun networkLevel(exp: Double): Double {
        if (exp < 0) return 1.0
        val level = floor(1 + REVERSE_PQ_PREFIX + sqrt(REVERSE_CONST + GROWTH_DIVIDES_2 * exp))
        val start = totalExpToLevel(level)
        val next = totalExpToLevel(level + 1)
        return level + (exp - start) / (next - start)
    }

    private fun totalExpToLevel(level: Double) = (HALF_GROWTH * (level - 2) + BASE) * (level - 1)

    /** a/b as Hypixel players write it (b = 0 counts as 1). */
    fun ratio(a: Int, b: Int) = a.toDouble() / maxOf(b, 1)

    /** A Bedwars prestige's colour (each 100 stars), as Hypixel colours the star; 1000 and above gold. */
    fun prestigeColor(stars: Int): Int = PRESTIGE.getOrElse(stars / 100) { 0xFFFFAA00.toInt() }

    private val PRESTIGE = intArrayOf(
        0xFFAAAAAA.toInt(), // stone: gray
        0xFFFFFFFF.toInt(), // iron: white
        0xFFFFAA00.toInt(), // gold
        0xFF55FFFF.toInt(), // diamond: aqua
        0xFF00AA00.toInt(), // emerald: dark green
        0xFF00AAAA.toInt(), // sapphire: dark aqua
        0xFFAA0000.toInt(), // ruby: dark red
        0xFFFF55FF.toInt(), // crystal: light purple
        0xFF5555FF.toInt(), // opal: blue
        0xFFAA00AA.toInt(), // amethyst: dark purple
    )

    private fun JsonObject.obj(k: String) = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.int(k: String) = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private const val BASE = 10_000.0
    private const val GROWTH = 2_500.0
    private const val HALF_GROWTH = 0.5 * GROWTH
    private const val REVERSE_PQ_PREFIX = -(BASE - 0.5 * GROWTH) / GROWTH
    private const val REVERSE_CONST = REVERSE_PQ_PREFIX * REVERSE_PQ_PREFIX
    private const val GROWTH_DIVIDES_2 = 2 / GROWTH
}
