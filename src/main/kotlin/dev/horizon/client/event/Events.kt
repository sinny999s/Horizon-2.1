package dev.horizon.client.event

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.Packet
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An event; [cancel] marks it cancelled for the code that posted it (other handlers still run). Shared
 * instances are reset to not cancelled before each post.
 */
abstract class Event {
    var cancelled = false

    fun cancel() {
        cancelled = true
    }
}

/** `Minecraft.tick` HEAD. */
class TickEvent : Event()

/** `Minecraft.tick` RETURN. */
class PostTickEvent : Event()

/** `Minecraft.runTick`, right after `runAllTasks()` (once per frame). */
class PostTasksEvent : Event()

/** `LocalPlayer.tick` HEAD. */
class PlayerUpdateEvent : Event()

/** `LocalPlayer.sendPosition` HEAD; cancelling skips sending the position. */
class PreSyncEvent : Event()

/** `LocalPlayer.sendPosition` RETURN. */
class PostSyncEvent : Event()

/** `Connection.channelRead0` HEAD, on the network thread; cancelling drops the packet. */
class ReceivePacketEvent(val packet: Packet<*>) : Event()

/** After a packet was handled on the main thread (bundles are split into their packets). */
class AfterReceivePacketEvent(val packet: Packet<*>) : Event()

/** `Connection.send` HEAD; cancelling drops the packet. */
class SendPacketEvent(val packet: Packet<*>) : Event()

/** After a block update or chunk block delta was applied. */
class WorldUpdateEvent : Event()

/** After an entity was added to the client level. */
class EntitySpawnEvent(val entity: Entity) : Event()

/**
 * After the level is drawn, before the hand (`GameRenderer.renderLevel`, before `render3dHud`): [view] is the camera
 * rotation (no translation; world draws are camera-relative), [projection] the level's projection. Draw via `World3D`.
 */
class Render3DEvent(val view: Matrix4f, val projection: Matrix4f, val partialTick: Float) : Event()

/** `LivingEntity.setSprinting`: handlers may change [sprinting]. */
class SprintEvent(var sprinting: Boolean) : Event()

/** Before vanilla picks the crosshair target; cancelling skips vanilla's pick. */
class CrosshairEvent : Event()

/** `Inventory.setSelectedSlot` HEAD; cancelling keeps the current slot. */
class SelectSlotEvent(val slot: Int) : Event()

/** `MultiPlayerGameMode.handleContainerInput` HEAD; cancelling drops the click. */
class ContainerClickEvent(val slotId: Int, val button: Int, val input: ContainerInput) : Event()

/** Around vanilla's hotbar (`Hud.extractItemHotbar` HEAD, then RETURN). */
class HotbarRenderEvent(val pre: Boolean) : Event()

/** The client's 2D layer, after the world composites and before the HUD widgets. */
class Render2DEvent(val graphics: GuiGraphicsExtractor) : Event()

/** After `KeyboardInput.tick` read the keys into the movement input. */
class KeyboardInputEvent : Event()

/** The local player's `jumpFromGround` HEAD; cancelling replays the jump with [yaw], [y] and [xz]. */
class JumpEvent(var yaw: Float, var y: Float, var xz: Float) : Event()

/** Around the local player's `travel` ([pre] at HEAD, then RETURN); cancelling the pre event skips travel. */
class TravelEvent(val pre: Boolean) : Event()

/** The local player's `moveRelative`; cancelling adds [velocity] instead of vanilla's. */
class MoveRelativeEvent(val input: Vec3, val speed: Float) : Event() {
    var velocity: Vec3 = Vec3.ZERO
}

/** A firework boosting the local player; cancelling boosts along [vector]. */
class FireworkVectorEvent(var vector: Vec3) : Event()

/** Around `MultiPlayerGameMode.useItem` ([pre] at HEAD, then RETURN). */
class UseItemEvent(val pre: Boolean) : Event()

/** Every frame, just after vanilla turned the camera by the mouse (Aura's Legit rotation turns it too). */
object MouseTurnEvent : Event() {
    /** Timestamp (`System.nanoTime()`) of the last non-zero physical mouse delta in `MouseHandler`. */
    @Volatile
    var lastPhysicalMoveNs: Long = 0L

    fun movedWithinMs(ms: Long): Boolean {
        val t = lastPhysicalMoveNs
        return t != 0L && (System.nanoTime() - t) <= ms * 1_000_000L
    }
}

/** The local player's model about to be drawn: head yaw (relative to the body), pitch and body yaw, all settable. */
class LookRenderEvent(var yaw: Float, var pitch: Float, var bodyYaw: Float) : Event()

/** An entity about to be drawn (`EntityRenderDispatcher.shouldRender`); cancelling skips it this frame. */
class RenderEntityEvent(val entity: Entity) : Event()

/**
 * `LocalPlayer.move`, at the call to `super.move`: the local player moving by [movement]. Cancelling moves by [x], [y],
 * [z] instead (handlers set them and cancel).
 */
class MoveEvent(val type: MoverType, val movement: Vec3) : Event() {
    var x = movement.x
    var y = movement.y
    var z = movement.z
}

/** `LocalPlayer.isUsingItem` HEAD; one shared instance (it fires many times a frame). Cancelling reports "using". */
object UsingItemEvent : Event()

/** `MultiPlayerGameMode.releaseUsingItem` HEAD; one shared instance. Cancelling keeps the item in use. */
object StopUsingItemEvent : Event()

/**
 * The event bus: handlers run in descending priority, equal priorities in the order they were plugged.
 * Every handler runs even after a cancel.
 */
object EventBus {
    private class Listener(val owner: Any, val priority: Int, val order: Long, val fn: (Event) -> Unit)

    private val listeners = HashMap<Class<*>, CopyOnWriteArrayList<Listener>>()
    private var counter = 0L

    @Synchronized
    fun register(owner: Any, type: Class<out Event>, priority: Int, fn: (Event) -> Unit) {
        val list = listeners.getOrPut(type) { CopyOnWriteArrayList() }
        val l = Listener(owner, priority, counter++, fn)
        val index = list.indexOfFirst { it.priority < priority }
        if (index < 0) list.add(l) else list.add(index, l)
    }

    @Synchronized
    fun unregister(owner: Any) {
        for (list in listeners.values) list.removeIf { it.owner === owner }
    }

    fun <E : Event> post(event: E): E {
        val list = listeners[event.javaClass] ?: return event
        for (l in list) l.fn(event)
        return event
    }

    inline fun <reified E : Event> on(owner: Any, priority: Int = 0, noinline fn: (E) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        register(owner, E::class.java, priority, fn as (Event) -> Unit)
    }
}
