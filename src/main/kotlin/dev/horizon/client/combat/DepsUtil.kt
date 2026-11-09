package dev.horizon.client.combat

import dev.horizon.client.event.EventBus
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.event.StopUsingItemEvent
import dev.horizon.client.event.TickEvent
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Items
import kotlin.math.sqrt

private val mc get() = Minecraft.getInstance()

/**
 * Lag-back clock: the last server teleport that came more than 10 s after a pearl-throw sound (anyone's) or a chorus
 * fruit of ours. Its handlers are on the bus from the first [start] (a module constructor) on.
 */
object LagBack {
    @Volatile
    private var last = -1L

    @Volatile
    private var intentional = -1L

    /** Ms since the last lag-back (huge before the first). */
    fun msSince() = System.currentTimeMillis() - last

    fun start() {}

    init {
        EventBus.on<ReceivePacketEvent>(this) { e ->
            val p = mc.player ?: return@on
            val now = System.currentTimeMillis()
            when (val pk = e.packet) {
                is ClientboundPlayerPositionPacket -> if (now - intentional - 10000 > 0 && p.tickCount > 20) last = now
                is ClientboundSoundPacket -> if (pk.sound.value() == SoundEvents.ENDER_PEARL_THROW) intentional = now
            }
        }
        EventBus.on<SendPacketEvent>(this) { e ->
            val p = mc.player ?: return@on
            if (e.packet is ServerboundUseItemPacket && p.useItem.item == Items.CHORUS_FRUIT) intentional = System.currentTimeMillis()
        }
        EventBus.on<StopUsingItemEvent>(this) {
            val p = mc.player ?: return@on
            if (p.useItem.item == Items.CHORUS_FRUIT) intentional = System.currentTimeMillis()
        }
    }
}

/** Movement bits: movement input and the 3D speed of the last tick. */
object Motion {
    /** Blocks moved between the last two ticks, measured at TickEvent. */
    @Volatile
    var speed3D = 0f
        private set

    /** Any WASD movement input (not velocity). */
    fun isMoving(): Boolean {
        val p = mc.player ?: return false
        if (mc.level == null) return false
        val v = p.input.moveVector
        return v.y != 0f || v.x != 0f
    }

    fun start() {}

    init {
        EventBus.on<TickEvent>(this) {
            val p = mc.player ?: return@on
            val dx = p.x - p.xo
            val dy = p.y - p.yo
            val dz = p.z - p.zo
            speed3D = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        }
    }
}
