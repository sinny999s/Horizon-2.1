package dev.horizon.client.module.player

import com.mojang.blaze3d.platform.InputConstants
import dev.horizon.client.event.FinishUsingItemEvent
import dev.horizon.client.event.InputEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.World3D
import dev.horizon.client.setting.Bind
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import java.awt.Color
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Server teleports that arrive while eating a chorus fruit, or after one was eaten, are
 * held back (an outline marks the first one's spot) until the confirm key is released.
 */
class ChorusDelay : Module("chorus-delay", Categories.PLAYER) {
    val confirm = bind("confirm", Bind(InputConstants.KEY_LSHIFT))
    val colorSetting = color("color", Color(0xFFFFFFFF.toInt(), true))

    /** A chorus fruit was eaten since the last release. */
    @Volatile
    private var ate = false

    /** Filled on the network thread. */
    private val held = ConcurrentLinkedQueue<ClientboundPlayerPositionPacket>()

    init {
        on<Render3DEvent> {
            val pos = held.peek()?.change()?.position() ?: return@on
            World3D.outline(AABB(pos.add(-0.27, 0.0, -0.27), pos.add(0.27, 1.8, 0.27)), colorSetting.value.rgb)
        }
        on<InputEvent> { e ->
            // 26.3's key and button codes overlap, so the device must match too.
            if (e.key != confirm.value.key || (e.device == InputEvent.Device.Mouse) != confirm.value.mouse) return@on
            if (e.action == InputEvent.Action.Release) release()
        }
        on<ReceivePacketEvent> { e ->
            val packet = e.packet as? ClientboundPlayerPositionPacket ?: return@on
            val p = mc.player ?: return@on
            if (!ate && p.useItem.item != Items.CHORUS_FRUIT) return@on
            held.add(packet)
            e.cancel()
        }
        on<FinishUsingItemEvent> { e ->
            if (e.item.item == Items.CHORUS_FRUIT) ate = true
        }
    }

    override fun onEnable() {
        held.clear()
        ate = false
    }

    override fun onDisable() {
        if (ate) release()
    }

    /** The held teleports, handled in the order they came. */
    private fun release() {
        ate = false
        val listener = mc.connection ?: return
        while (true) held.poll()?.handle(listener) ?: break
    }
}
