package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.misc.LunarSpoof
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * Lunar Client's side of Apollo's `packet_enrichment`, `combat` and `server_rule` modules, on a connection where Lunar Spoof
 * talks Apollo, as Lunar Client 26.x does them ([ApolloRules] keeps what the server turned on; mixins in `misc2/Apollo*`).
 * Packet enrichment: extra serverbound messages on `lunar:apollo` ([ApolloProto]), each only while the server has it on.
 * - Attack: each attack on a player under your crosshair (you, them, and the hit's squared distance to their feet).
 * - Chat open / close: when the chat screen sets itself up (so also when the window is resized) and when it closes.
 * - Inventory open / close: your own inventory (survival or creative), never a container.
 * - Item use: each use, main hand or offhand. Lunar's 26.x jar never sends the bucket message, and neither does this.
 * Locations carry the world the server last named (UpdatePlayerWorldMessage). Combat: missed swings cost nothing and
 * digging and using combine, when the server allows it. Server rules: the chat box's length.
 */
object ApolloEnrichment {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** What this server's Apollo settings turn on (since joining). */
    @Volatile
    var rules = ApolloRules()
        private set

    /** The world the server last named, for the locations ("" until it does). */
    @Volatile
    var world = ""
        private set

    /** Joining a server: nothing's on until its settings say so. */
    fun reset() {
        rules = ApolloRules()
        world = ""
    }

    fun world(name: String) {
        world = name
    }

    /** The rules, while Lunar Spoof talks Apollo here (a server, the module and its `apollo` on), else null. */
    private fun live(): ApolloRules? {
        val m = LunarSpoof.instance ?: return null
        val conn = mc.connection ?: return null
        if (!m.enabled || !m.apollo.value || conn.connection.isMemoryConnection) return null
        return rules
    }

    private fun self(p: LocalPlayer): ApolloProto.Player {
        val move = p.input.moveVector
        return ApolloProto.Player(p.uuid, world, p.x, p.y, p.z, p.yRot, p.xRot, p.isShiftKeyDown, p.isSprinting, true,
            p.input.keyPresses.jump(), move.y, move.x)
    }

    private fun other(p: Player) = ApolloProto.Player(p.uuid, world, p.x, p.y, p.z, p.yRot, p.xRot, p.isShiftKeyDown, p.isSprinting, false)

    private fun send(what: String, bytes: ByteArray) {
        LunarApollo.send(ApolloProto.APOLLO, bytes)
        HorizonClient.LOG.debug("Lunar Spoof: Apollo packet enrichment: {}", what)
    }

    /** MultiPlayerGameMode.attack: the target, if it's the player under the crosshair. */
    @JvmStatic
    fun attack(target: Entity) {
        val r = live() ?: return
        if (!r.enrich("player-attack") || target !is Player) return
        val p = mc.player ?: return
        val hit = mc.hitResult as? EntityHitResult ?: return
        if (hit.entity !== target) return
        send("attack", ApolloProto.attack(System.currentTimeMillis(), self(p), other(target), hit.distanceTo(target)))
    }

    /** MultiPlayerGameMode.useItem, once the held item is synced. */
    @JvmStatic
    fun useItem(hand: InteractionHand) {
        val r = live() ?: return
        if (!r.enrich("player-use-item")) return
        val p = mc.player ?: return
        send("use item", ApolloProto.useItem(System.currentTimeMillis(), self(p), hand == InteractionHand.MAIN_HAND))
    }

    /** ChatScreen.init (open) and ChatScreen.removed (close). */
    @JvmStatic
    fun chat(open: Boolean) {
        val r = live() ?: return
        if (!r.enrich(if (open) "player-chat-open" else "player-chat-close")) return
        val p = mc.player ?: return
        send(if (open) "chat open" else "chat close", ApolloProto.chat(open, System.currentTimeMillis(), self(p)))
    }

    private fun inventory(s: Screen?) = s is InventoryScreen || s is CreativeModeInventoryScreen

    /** A screen change (Gui.setScreen went through): your inventory closing and opening. */
    @JvmStatic
    fun screenChanged(old: Screen?, now: Screen?) {
        if (old === now) return
        val r = live() ?: return
        val p = mc.player ?: return
        if (inventory(old) && r.enrich("player-inventory-close")) send("inventory close", ApolloProto.inventory(false, System.currentTimeMillis(), self(p)))
        if (inventory(now) && r.enrich("player-inventory-open")) send("inventory open", ApolloProto.inventory(true, System.currentTimeMillis(), self(p)))
    }

    /** Minecraft.startAttack's start: combat's miss penalty lifted, as Lunar does (the swing cooldown set to 0). */
    @JvmStatic
    fun startAttack() {
        val r = live() ?: return
        if (r.noMissPenalty(mc.hitResult?.type == HitResult.Type.BLOCK)) (mc as MinecraftAccessor).horizon_setMissTime(0)
    }

    /** Combat's `allow-dig-and-use` on this server. */
    @JvmStatic
    fun digAndUse(): Boolean = live()?.allowDigAndUse() == true

    /** Server rules' chat length on this server (null: vanilla's). */
    @JvmStatic
    fun maxChatLength(): Int? = live()?.maxChatLength()
}
