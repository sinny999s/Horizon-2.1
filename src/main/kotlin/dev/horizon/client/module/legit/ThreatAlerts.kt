package dev.horizon.client.module.legit

import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.TickEvent
import dev.horizon.client.hud.NotificationType
import dev.horizon.client.hud.Notifications
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Warns about the things that start most Bedwars deaths, from what the server already sends (it
 * sends nothing itself, so nothing can see it). An enemy within [range] switching to a fireball, TNT, a bow or an
 * ender pearl, or turning invisible; and, in a game (see [inGame]), around your own bed (the one nearest where you
 * first stood in this world): an enemy coming within [bedRange], or an enemy breaking a block next to it. Each alert is a notification, with a sound
 * and a chat line if wanted, and the same one doesn't repeat for [COOLDOWN_MS].
 */
class ThreatAlerts : Module("threat-alerts", Categories.LEGIT) {
    val range = int("range", 32, 8..96)
    val fireballs = bool("fireballs", true)
    val tnt = bool("tnt", true)
    val bows = bool("bows", true)
    val pearls = bool("pearls", false)
    val invisibility = bool("invisibility", true)
    val bed = bool("bed", true)
    val bedRange = int("bed-range", 10, 3..32) { bed.value }
    val sound = bool("sound", true)
    val chat = bool("chat", false)

    private enum class Kind(val what: String) { FIREBALL("a fireball"), TNT("TNT"), BOW("a bow"), PEARL("an ender pearl") }

    private val holding = HashMap<UUID, Kind?>()
    private val invisible = HashMap<UUID, Boolean>()
    private val lastAlert = HashMap<String, Long>()
    private val nearBed = HashSet<UUID>()

    private var level: ClientLevel? = null
    private var ticksIn = 0
    private var lastPos: Vec3? = null
    private var bedPos: BlockPos? = null
    /** The bed was found and is gone since: no other bed is taken for it until the next world. */
    private var bedLost = false

    override fun onEnable() = clear()

    private fun clear() {
        holding.clear()
        invisible.clear()
        lastAlert.clear()
        nearBed.clear()
        ticksIn = 0
        bedPos = null
        bedLost = false
    }

    init {
        on<TickEvent> {
            val p = mc.player ?: return@on
            val lvl = mc.level ?: return@on
            // A new world, or a long teleport (a game starting): everything is looked at afresh.
            val moved = lastPos?.distanceTo(p.position()) ?: 0.0
            lastPos = p.position()
            if (lvl !== level || moved > 64) {
                level = lvl
                clear()
            }
            ticksIn++
            if (bed.value && inGame(p)) watchBed(p, lvl)
            val r2 = range.value * range.value.toDouble()
            val seen = HashSet<UUID>()
            for (other in lvl.players()) {
                if (other === p || Teams.isTeammate(other) || other.isSpectator) continue
                val id = other.uuid
                seen += id
                if (other.distanceToSqr(p) > r2) {
                    holding.remove(id)
                    continue
                }
                val kind = kindOf(other.mainHandItem)
                val known = id in holding
                val before = holding.put(id, kind)
                // On the switch to it, not the whole time it's held, nor for what they held when first seen.
                if (kind != null && kind != before && known && enabledFor(kind)) {
                    alert("$id/${kind.name}", "${other.name.string} has ${kind.what}", other.mainHandItem.copy())
                }
                val inv = other.isInvisible
                if (invisibility.value && inv && invisible[id] == false) {
                    alert("$id/invis", "${other.name.string} went invisible", ItemStack(Items.POTION))
                }
                invisible[id] = inv
            }
            holding.keys.retainAll(seen)
            invisible.keys.retainAll(seen)
        }

        on<ReceivePacketEvent> { e ->
            if (!bed.value) return@on
            val home = bedPos ?: return@on
            val p = mc.player ?: return@on
            if (!inGame(p)) return@on
            when (val pk = e.packet) {
                is ClientboundBlockDestructionPacket -> {
                    if (pk.id == p.id || pk.progress !in 0..9 || pk.pos.distManhattan(home) > BED_BLOCKS) return@on
                    // The server says who's breaking it: a teammate's (or anything not a player's) isn't news.
                    val breaker = mc.level?.getEntity(pk.id) as? Player
                    if (breaker == null || Teams.isTeammate(breaker)) return@on
                    alert("bed/break", "${breaker.name.string} is breaking blocks at your bed", bedIcon(home))
                }
                is ClientboundBlockUpdatePacket -> {
                    if (!pk.blockState.isAir || pk.pos.distManhattan(home) > BED_BLOCKS) return@on
                    // Here it doesn't say who: only news when an enemy is close enough to have done it (so not
                    // your own breaking, or a teammate's).
                    val at = Vec3.atCenterOf(pk.pos)
                    val enemy = mc.level?.players()?.any { it !== p && !Teams.isTeammate(it) && !it.isSpectator &&
                        it.eyePosition.distanceTo(at) <= BREAK_REACH } == true
                    if (enemy) alert("bed/gone", "A block at your bed was broken", bedIcon(home))
                }
            }
        }
    }

    private fun enabledFor(k: Kind) = when (k) {
        Kind.FIREBALL -> fireballs.value
        Kind.TNT -> tnt.value
        Kind.BOW -> bows.value
        Kind.PEARL -> pearls.value
    }

    private fun kindOf(s: ItemStack): Kind? = when {
        s.`is`(Items.FIRE_CHARGE) -> Kind.FIREBALL
        s.`is`(Items.TNT) -> Kind.TNT
        s.`is`(Items.BOW) || s.`is`(Items.CROSSBOW) -> Kind.BOW
        s.`is`(Items.ENDER_PEARL) -> Kind.PEARL
        else -> null
    }

    /**
     * In a Bedwars game rather than a lobby: every team's players wear leather armour dyed their colour, which lobbies
     * (full of decoration beds) never put on you. Bed alerts only run then.
     */
    private fun inGame(p: Player): Boolean = DYED_SLOTS.any { slot ->
        val dyed = p.getItemBySlot(slot).get(DataComponents.DYED_COLOR) ?: return@any false
        TeamMatcher.normalizeArmorColor(dyed.rgb()) != null
    }

    /** Finds your bed once you've been in the world a moment, then watches who comes near it. */
    private fun watchBed(p: Player, lvl: ClientLevel) {
        val home = bedPos
        if (home == null) {
            // Looked for once a second (it's a sizeable scan), from a moment after arriving: you've spawned by it then.
            if (bedLost || ticksIn < FIND_AFTER || ticksIn % 20 != 0) return
            bedPos = nearestBed(p, lvl)
            return
        }
        if (lvl.getBlockState(home).block !is BedBlock) {
            bedPos = null
            bedLost = true
            return
        }
        val r2 = bedRange.value * bedRange.value.toDouble()
        val center = Vec3.atCenterOf(home)
        for (other in lvl.players()) {
            if (other === p || Teams.isTeammate(other) || other.isSpectator) continue
            val close = other.distanceToSqr(center) <= r2
            if (close && nearBed.add(other.uuid)) {
                alert("${other.uuid}/bed", "${other.name.string} is at your bed", bedIcon(home))
            } else if (!close) {
                nearBed.remove(other.uuid)
            }
        }
    }

    private fun nearestBed(p: Player, lvl: ClientLevel): BlockPos? {
        val c = p.blockPosition()
        var best: BlockPos? = null
        var bestD = Double.MAX_VALUE
        val m = BlockPos.MutableBlockPos()
        for (x in -BED_SEARCH..BED_SEARCH) for (y in -8..8) for (z in -BED_SEARCH..BED_SEARCH) {
            m.set(c.x + x, c.y + y, c.z + z)
            if (lvl.getBlockState(m).block !is BedBlock) continue
            val d = m.distSqr(c)
            if (d < bestD) {
                bestD = d
                best = m.immutable()
            }
        }
        return best
    }

    /** Your bed's own item (its team colour) for the notification. */
    private fun bedIcon(home: BlockPos): ItemStack = ItemStack(mc.level?.getBlockState(home)?.block?.asItem() ?: Items.FIRE_CHARGE)

    private fun alert(key: String, text: String, icon: ItemStack) {
        val now = System.currentTimeMillis()
        if (now - (lastAlert[key] ?: 0L) < COOLDOWN_MS) return
        lastAlert[key] = now
        Notifications.post("Threat", text, 3f, NotificationType.WARNING, icon)
        if (chat.value) moduleMessage(name.trim(), text)
        if (sound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.6f))
    }

    private companion object {
        const val COOLDOWN_MS = 6000L
        /** Ticks in a world before looking for your bed: you've spawned beside it by then. */
        const val FIND_AFTER = 40
        const val BED_SEARCH = 24
        /** Blocks around the bed (by Manhattan distance) that count as its defence. */
        const val BED_BLOCKS = 4
        /** How far from a broken block a player could have broken it (survival reach, and a little). */
        const val BREAK_REACH = 5.5
        val DYED_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    }
}
