package dev.horizon.client.hud

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the HUD widgets read about the world and the server: block entities around the player, ping,
 * TPS from time packets, movement speed, totem pops and inventory counts.
 */
object ServerInfo {
    private val mc get() = Minecraft.getInstance()

    /** Block entities of every loaded chunk within render distance, rebuilt every tick at PlayerUpdate +10. */
    var blockEntities: List<BlockEntity> = emptyList()
        private set

    /** The local player's latency from the tab list (0 in singleplayer). */
    var ping = 0
        private set

    /** Horizontal and 3-D distance moved last tick, blocks/tick. */
    var horizontalSpeed = 0f
        private set
    var speed = 0f
        private set

    private var lastTimePacket = 0L
    private var interval = 0L
    private val samples = ArrayDeque<Float>()
    private var average = 0f
    private val pops = HashMap<String, Int>()

    fun rebuildBlockEntities() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val rd = mc.options.renderDistance().get()
        // Divides with truncation, so the square is off by one chunk at negative coordinates (on purpose).
        val cx = p.x.toInt() / 16
        val cz = p.z.toInt() / 16
        val list = ArrayList<BlockEntity>()
        for (i in -rd..rd) for (j in -rd..rd) level.chunkSource.getChunkNow(cx + i, cz + j)?.let { list.addAll(it.blockEntities.values) }
        blockEntities = list
    }

    fun tick() {
        val p = mc.player ?: return
        ping = mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
        val dx = p.x - p.xo
        val dy = p.y - p.yo
        val dz = p.z - p.zo
        horizontalSpeed = hypot(dx, dz).toFloat()
        speed = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    /** Ping of another player from the tab list. */
    fun ping(player: Player): Int =
        if (player === mc.player) ping else mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0

    fun timePacket() {
        val now = System.currentTimeMillis()
        if (lastTimePacket != 0L) {
            interval = now - lastTimePacket
            val p = mc.player
            if (p != null && p.tickCount < 20) {
                samples.clear()
                return
            }
            if (interval > 0) {
                if (samples.size >= 20) samples.removeFirst()
                val v = 20f * factor()
                if (!v.isNaN() && !v.isInfinite()) samples.add(v)
                average = samples.average().toFloat()
            }
        }
        lastTimePacket = now
    }

    /** 1000 / ms between the last two time packets (1 on mioclient): AutoCrystal's damage-sync scales by it. */
    fun tpsFactor(): Float = factor()

    private fun factor(): Float =
        if (mc.currentServer?.ip?.contains("pvp.mioclient.me") == true) 1f else 1000f / max(interval.toFloat(), 1f)

    /** The TPS from the last two time packets, to 2 decimals. */
    fun tps(): Float = BigDecimal(20.0 * factor()).setScale(2, RoundingMode.HALF_UP).toFloat()

    /** The average of the last 20. */
    fun averageTps(): Float = BigDecimal(average.toDouble()).setScale(2, RoundingMode.HALF_UP).toFloat()

    /** Fewer than 20 samples yet. */
    fun warmingUp() = samples.size < 20

    fun entityEvent(packet: ClientboundEntityEventPacket) {
        if (packet.eventId.toInt() != 35) return
        val p = packet.getEntity(mc.level ?: return) as? Player ?: return
        pops.merge(p.gameProfile.name, 1, Int::plus)
    }

    fun pops(p: Player) = pops[p.gameProfile.name] ?: 0

    /** The whole inventory, armour and offhand included. */
    fun count(item: Item): Int {
        val inv = mc.player?.inventory ?: return 0
        var n = 0
        for (i in 0 until minOf(45, inv.containerSize)) {
            val s = inv.getItem(i)
            if (s.`is`(item)) n += s.count
        }
        return n
    }
}
