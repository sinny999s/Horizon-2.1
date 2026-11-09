package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.misc.LunarSpoof
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
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
 * digging and using combine, when the server allows it. Server rules:
 * - the chat box's length; brightness (the gamma used, not saved in your options); a portal leaving the chat open
 *   (only the chat: other screens still close); F3+A doing nothing; Iris's shaders turned off (Lunar turns off its own
 *   shader pack; Horizon's shaders are Iris's).
 * - Crystal optimizer: hitting an end crystal removes it on your side at once when the hit can hurt it, and doesn't
 *   send a hit that can't (Weakness).
 * - A competitive game: the pause menu's Disconnect, and the server's competitive commands (`/hub`...), ask first
 *   with Lunar's warning; a confirmed command is sent once more and let through.
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

    /** Server rules' gamma on this server (null: the video setting's). */
    @JvmStatic
    fun gamma(): Float? = live()?.gamma()

    /** Server rules' `anti-portal-traps`: the portal doesn't close your screen when it's the chat. */
    @JvmStatic
    fun keepInPortal(): Boolean = live()?.antiPortalTraps() == true && mc.gui.screen() is ChatScreen

    /** Server rules' `disable-chunk-reloading`: F3+A does nothing. */
    @JvmStatic
    fun noChunkReloading(): Boolean = live()?.noChunkReloading() == true

    /**
     * MultiPlayerGameMode.attack's start, crystal optimizer: an end crystal the hit can hurt is removed on your side (the
     * hit still goes); one it can't is not hit (true: cancel the attack).
     */
    @JvmStatic
    fun crystalAttack(target: Entity): Boolean {
        if (target !is EndCrystal || live()?.crystalOptimizer() != true) return false
        val p = mc.player ?: return false
        var item = 0.0
        p.mainHandItem.get(DataComponents.ATTRIBUTE_MODIFIERS)?.forEach(EquipmentSlot.MAINHAND) { a, m ->
            if (a == Attributes.ATTACK_DAMAGE) item += m.amount()
        }
        val hurts = ApolloRules.crystalHitDamages(p.getEffect(MobEffects.WEAKNESS)?.amplifier, p.getEffect(MobEffects.STRENGTH)?.amplifier,
            p.getAttributeValue(Attributes.ATTACK_DAMAGE), item)
        if (!hurts) return true
        mc.level?.removeEntity(target.id, Entity.RemovalReason.DISCARDED)
        return false
    }

    /** Lunar's warning before leaving a competitive game: [then] on "I UNDERSTAND, CONTINUE", back to [back] on Cancel. */
    private fun competitiveWarning(back: Screen?, then: () -> Unit) {
        mc.gui.setScreen(ConfirmScreen({ ok -> if (ok) then() else mc.gui.setScreen(back) },
            Component.literal("WARNING!"),
            Component.literal("Leaving competitive games can result in penalties.\nYou may be suspended from competitive games if you continue leaving games!"),
            Component.literal("I UNDERSTAND, CONTINUE"), Component.literal("Cancel")))
    }

    /** The pause menu's Disconnect (its runnable, from [pause]): in a competitive game, asks first. */
    @JvmStatic
    fun disconnectButton(run: Runnable, pause: Screen): Runnable {
        if (live()?.competitiveGame() != true) return run
        return Runnable { competitiveWarning(pause) { run.run() } }
    }

    /** A command let through once, after its warning was confirmed. */
    @Volatile
    private var allowOnce: String? = null

    /**
     * ClientPacketListener.sendCommand / sendUnattendedCommand: a competitive command in a competitive game waits for
     * the warning (true: not sent now). Confirmed, it's sent again and let through.
     */
    @JvmStatic
    fun holdCommand(command: String, unattended: Screen?, isUnattended: Boolean): Boolean {
        if (allowOnce == command) {
            allowOnce = null
            return false
        }
        if (live()?.competitiveCommand("/$command") != true) return false
        // Next frame: the chat screen sending this closes itself after.
        dev.horizon.client.combat.Scheduler.ms(0) {
            competitiveWarning(null) {
                allowOnce = command
                mc.gui.setScreen(null)
                val c = mc.connection ?: return@competitiveWarning
                if (isUnattended) c.sendUnattendedCommand(command, unattended) else c.sendCommand(command)
            }
        }
        return true
    }

    /** After the server's settings change: `disable-shaders` turns Iris's shaders off, when Iris is installed. */
    fun applied() {
        if (live()?.noShaders() == true) mc.execute { shadersOff() }
    }

    private fun shadersOff() {
        try {
            val api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null)
            val config = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getConfig").invoke(api)
            val type = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")
            if (type.getMethod("areShadersEnabled").invoke(config) != true) return
            type.getMethod("setShadersEnabledAndApply", Boolean::class.javaPrimitiveType).invoke(config, false)
            HorizonClient.LOG.info("Lunar Spoof: this server's Apollo rules turn shaders off; turned Iris's shaders off")
            LunarSpoof.instance?.let { dev.horizon.client.combat.moduleMessage(it.name.trim(), "this server turns shaders off (as Lunar does)") }
        } catch (e: ClassNotFoundException) {
            // No Iris: nothing to turn off.
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Lunar Spoof: couldn't turn Iris's shaders off: {}", e.toString())
        }
    }
}
