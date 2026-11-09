package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * The rest of your Lunar profile, changed the way Lunar Client changes it, over Horizon's Lunar
 * connection ([LunarNetwork]):
 * - Badge: which of your badges shows by your name (BadgeService.EquipBadge).
 * - Lunar+ colour: the plus on your logo, from the colours Lunar offers you (CosmeticService.UpdateLunarPlusColor).
 * - Outfits: all of them, from Lunar's Login ([LunarAccount] keeps the list): wear one (the tree's default, via
 *   SelectOutfit), make, rename and delete them, and set outfits that are worn instead under a condition (on a server,
 *   in a dimension, at night...: the tree's conditional outfits).
 * - Emote wheel: which emote sits in each slot (EmoteService.UpdateEquippedEmotes), played from the wheel ([LunarEmoteWheel]).
 * Shown in the locker's Outfits and Badges tabs and its Emotes tab.
 */
object LunarProfileEdits {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** Wheel slots. */
    const val SLOTS = 8

    @Volatile
    var ownedBadges: List<Int> = emptyList()
        private set

    @Volatile
    var allBadges = false
        private set

    @Volatile
    var badge = 0
        private set

    @Volatile
    var plusColors: List<Int> = emptyList()
        private set

    @Volatile
    var wheel: Map<Int, Int> = emptyMap()
        private set

    /** Jams (emote music) attached to your wheel's emotes: emote to jam. */
    @Volatile
    var jams: Map<Int, Int> = emptyMap()
        private set

    // ---- from LunarNetwork (socket thread) ----

    fun badgeLogin(out: ByteArray) {
        val (owned, all) = LunarWire.badgeLogin(out)
        ownedBadges = owned.distinct()
        allBadges = all
        badge = LunarWire.loginBadge(out)
    }

    fun cosmeticLogin(out: ByteArray) {
        plusColors = LunarWire.loginPlusColors(out)
    }

    fun emoteLogin(out: ByteArray) {
        wheel = LunarSocialWire.emoteWheel(out)
        jams = LunarSocialWire.wheelJams(out)
    }

    fun clear() {
        ownedBadges = emptyList()
        allBadges = false
        badge = 0
        plusColors = emptyList()
        wheel = emptyMap()
        jams = emptyMap()
    }

    // ---- badge and Lunar+ colour (game thread) ----

    /** The badges you can wear: owned ones (every one with all of them). */
    fun badges(): List<Int> = if (allBadges) LunarBadges.all().map { it.id } else ownedBadges

    /** Wears badge [id] (0: none). */
    fun equipBadge(id: Int) {
        val sent = LunarNetwork.request(LunarWire.BADGE_SERVICE, "EquipBadge", LunarWire.equipBadge(id)) {
            badge = id
            val self = mc.user.profileId
            LunarNetwork.peer(self)?.let { LunarNetwork.addPeer(self, it.logo, it.plus, id) }
            tell(if (id == 0) "badge taken off" else "now wearing the ${LunarBadges.badge(id)?.name ?: "#$id"} badge")
        }
        if (!sent) tell("not connected to Lunar")
    }

    /** Your Lunar+ colour, as one of [plusColors]. */
    fun setPlusColor(argb: Int) {
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "UpdateLunarPlusColor", LunarWire.updatePlusColor(argb)) {
            val self = mc.user.profileId
            LunarNetwork.peer(self)?.let { LunarNetwork.addPeer(self, it.logo, argb or (0xFF shl 24), it.badge) }
        }
        if (!sent) tell("not connected to Lunar")
    }

    fun plusColor(): Int = LunarNetwork.peer(mc.user.profileId)?.plus ?: 0

    // ---- outfits (game thread) ----

    fun outfits() = LunarAccount.allOutfits()
    fun tree() = LunarWire.tree(LunarAccount.outfitTree())

    /** Wears [o] by default (the tree's default outfit; conditional ones kept). */
    fun wear(o: LunarWire.Outfit) {
        val id = o.id ?: return
        val t = tree().copy(default = id)
        select(t) { tell("wearing your outfit \"${o.name}\"") }
    }

    private fun select(t: LunarWire.Tree, done: () -> Unit) {
        val bytes = LunarWire.treeBytes(t)
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "SelectOutfit", LunarWire.selectOutfitRequest(bytes)) { r ->
            if (LunarWire.outfitResponse(r).first == 1) {
                LunarAccount.setTree(bytes)
                done()
            } else tell("Lunar didn't change your outfits")
        }
        if (!sent) tell("not connected to Lunar")
    }

    fun create(name: String) {
        val n = name.trim().ifEmpty { "Outfit ${outfits().size + 1}" }
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "CreateOutfit", LunarWire.createOutfitRequest(n)) { r ->
            val (status, o) = LunarWire.outfitResponse(r)
            if (status == 1 && o != null) {
                LunarAccount.putOutfit(o)
                tell("made the outfit \"${o.name}\"")
            } else tell(if (status == 2) "you have as many outfits as Lunar allows" else "Lunar didn't make the outfit")
        }
        if (!sent) tell("not connected to Lunar")
    }

    fun rename(o: LunarWire.Outfit, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "UpdateOutfit", LunarWire.updateOutfitRequest(LunarWire.withName(o, n))) { r ->
            val (status, saved) = LunarWire.outfitResponse(r)
            if (status == 1) LunarAccount.putOutfit(saved ?: LunarWire.outfit(LunarWire.withName(o, n))) else tell("Lunar didn't rename it")
        }
        if (!sent) tell("not connected to Lunar")
    }

    fun delete(o: LunarWire.Outfit) {
        val id = o.id ?: return
        if (tree().default == id) {
            tell("wear another outfit before deleting this one")
            return
        }
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "DeleteOutfit", LunarWire.deleteOutfitRequest(id)) { r ->
            if (LunarWire.outfitResponse(r).first == 1) {
                LunarAccount.removeOutfit(id)
                // Its conditions go with it.
                val t = tree()
                if (t.rules.any { it.second == id }) select(t.copy(rules = t.rules.filter { it.second != id })) {}
                tell("deleted the outfit \"${o.name}\"")
            } else tell("Lunar didn't delete it")
        }
        if (!sent) tell("not connected to Lunar")
    }

    /** Wears [o] instead while [c] holds (a conditional outfit, checked before the ones already set). */
    fun addRule(c: LunarWire.Condition, o: LunarWire.Outfit) {
        val id = o.id ?: return
        val t = tree()
        select(t.copy(rules = listOf(c to id) + t.rules.filter { it.first != c })) { tell("\"${o.name}\" is worn ${conditionText(c)}") }
    }

    fun removeRule(index: Int) {
        val t = tree()
        if (index !in t.rules.indices) return
        select(t.copy(rules = t.rules.filterIndexed { i, _ -> i != index })) {}
    }

    /** A condition, said ("on 6b6t.org", "at night"...). Game-free. */
    fun conditionText(c: LunarWire.Condition): String = when (c.kind) {
        LunarWire.Condition.SERVER -> "on ${c.text}"
        LunarWire.Condition.DIMENSION -> "in the " + (c.text ?: "?").substringAfter(':').replace('_', ' ')
        LunarWire.Condition.TIME -> if (c.value == 2) "at night" else "by day"
        LunarWire.Condition.WEATHER -> when (c.value) { 1 -> "in rain"; 2 -> "in snow"; 3 -> "in thunderstorms"; else -> "in clear weather" }
        LunarWire.Condition.MOVEMENT -> when (c.value) { 1 -> "while swimming"; 2 -> "while flying"; else -> "while gliding" }
        LunarWire.Condition.IN_LIQUID -> if (c.value == 2) "in lava" else "in water"
        LunarWire.Condition.ON_FIRE -> "while on fire"
        LunarWire.Condition.BIOME_TEMPERATURE -> if (c.value == 2) "in cold biomes" else "in hot biomes"
        LunarWire.Condition.PASSENGER -> "while riding " + (c.text ?: "?").substringAfter(':').replace('_', ' ')
        else -> "sometimes"
    }

    // ---- emote wheel (game thread) ----

    /** Puts emote [id] in wheel slot [slot] (0: empties it) and saves the wheel on your account. */
    fun setSlot(slot: Int, id: Int) {
        val w = LinkedHashMap(wheel)
        if (id == 0) w.remove(slot) else {
            w.entries.removeIf { it.value == id }
            w[slot] = id
        }
        wheel = w
        if (!LunarNetwork.request(LunarSocialWire.EMOTE_SERVICE, "UpdateEquippedEmotes", LunarSocialWire.updateWheel(w)) { }) {
            HorizonClient.LOG.info("Lunar: emote wheel kept here only (not connected)")
        }
    }

    /** For ShotBot: a wheel of [ids] in slot order; badges [badges] owned, [worn] worn; Lunar+ colours offered. */
    @JvmStatic
    fun testProfile(ids: IntArray, badges: IntArray, worn: Int) {
        // Two outfits: Main (worn) and PvP, worn instead on 6b6t.org.
        LunarAccount.testLogin(intArrayOf(8993, 8980))
        val main = LunarAccount.allOutfits().first()
        val pvp = LunarWire.outfit(LunarWire.message { o ->
            o.writeBytes(1, com.google.protobuf.ByteString.copyFrom(LunarWire.uuid(UUID.randomUUID())))
            o.writeString(2, "PvP")
            o.writeBytes(3, com.google.protobuf.ByteString.copyFrom(LunarWire.equippedCosmetic(8980)))
        })
        LunarAccount.putOutfit(pvp)
        LunarAccount.setTree(LunarWire.treeBytes(LunarWire.Tree(main.id, listOf(LunarWire.Condition(LunarWire.Condition.SERVER, 0, "6b6t.org") to pvp.id!!))))
        dev.horizon.client.gui.modern.LunarProfilePanes.testing = true
        wheel = ids.withIndex().associate { it.index to it.value }.filterValues { it != 0 }
        ownedBadges = badges.toList()
        badge = worn
        plusColors = listOf(0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFFFFAA00.toInt(), 0xFF55FF55.toInt())
    }

    private fun tell(text: String) = mc.execute { LunarSettings.tell(text) }
}
