package dev.horizon.client.lunar

import dev.horizon.client.HorizonClient
import dev.horizon.client.combat.moduleMessage
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Your Lunar account's outfit, changed the way Lunar Client's locker changes it. Cosmetics your
 * account owns that you pick or take off in Horizon's locker are put on or taken off your current Lunar outfit
 * (CosmeticService.UpdateOutfit with the whole outfit, every other field as Lunar last sent it), so other Lunar players
 * see them. With no outfit yet, one called "Horizon" is made (CreateOutfit) and selected (SelectOutfit). Cosmetics the
 * account doesn't own never go to Lunar (its server wouldn't show them anyway). Only while Lunar Spoof's Lunar Network
 * is connected and its `locker-account` setting is on.
 */
object LunarAccount {
    private val mc: Minecraft get() = Minecraft.getInstance()

    @Volatile
    private var outfits: List<LunarWire.Outfit> = emptyList()

    @Volatile
    private var tree: ByteArray? = null

    @Volatile
    private var known = false

    /** From Lunar's Login answer (any thread). */
    fun login(list: List<LunarWire.Outfit>, outfitTree: ByteArray?) {
        outfits = list
        tree = outfitTree
        known = true
    }

    /**
     * For ShotBot (`lunarowned`): as if Lunar's Login said the account owns [ids] and wears an outfit "Main" with the
     * first of them. Nothing is sent; with no connection, picks fall back to client-side.
     */
    @JvmStatic
    fun testLogin(ids: IntArray) {
        LunarLocker.owned(ids.toSet(), false)
        val id = UUID.randomUUID()
        val outfit = LunarWire.outfit(run {
            val out = java.io.ByteArrayOutputStream()
            val c = com.google.protobuf.CodedOutputStream.newInstance(out)
            c.writeByteArray(1, LunarWire.uuid(id))
            c.writeString(2, "Main")
            ids.firstOrNull()?.let { first -> c.writeByteArray(3, LunarWire.equippedCosmetic(first)) }
            c.flush()
            out.toByteArray()
        })
        login(listOf(outfit), LunarWire.treeWithDefault(null, id))
    }

    fun clear() {
        outfits = emptyList()
        tree = null
        known = false
    }

    /** Every outfit Lunar has for you (the outfit manager, [LunarProfileEdits]). */
    fun allOutfits(): List<LunarWire.Outfit> = outfits

    /** The outfit tree as Lunar last had it (raw), or null. */
    fun outfitTree(): ByteArray? = tree

    /** The tree after a SelectOutfit Lunar took. */
    fun setTree(t: ByteArray) {
        tree = t
    }

    /** An outfit Lunar made or saved: replaces the one with its id, or is added. */
    fun putOutfit(o: LunarWire.Outfit) {
        outfits = if (outfits.any { it.id == o.id }) outfits.map { if (it.id == o.id) o else it } else outfits + o
    }

    fun removeOutfit(id: UUID) {
        outfits = outfits.filter { it.id != id }
    }

    /** The outfit worn now (the tree's default, else the first). */
    fun currentOutfit(): LunarWire.Outfit? = current()

    /** The outfit Lunar shows you in: the tree's default, else the first. */
    private fun current(): LunarWire.Outfit? {
        val id = tree?.let { LunarWire.treeDefault(it) }
        return outfits.firstOrNull { it.id == id } ?: outfits.firstOrNull()
    }

    /** Whether [id] is on your Lunar outfit now. */
    fun onAccount(id: Int) = current()?.cosmetics?.any { it.first == id } == true

    private val enabled: Boolean
        get() {
            return LunarSettings.network.value && LunarSettings.lockerAccount.value
        }

    /**
     * The outfit's cosmetics after putting on ([equip]) or taking off cosmetic [id]: putting one on replaces the outfit's
     * cosmetic of the same kind ([typeOf]); the rest, with their options, stay. Game-free.
     */
    fun plan(current: List<Pair<Int, ByteArray>>, typeOf: (Int) -> String?, id: Int, equip: Boolean): List<ByteArray> {
        val type = typeOf(id)
        val out = ArrayList<ByteArray>()
        for ((cid, raw) in current) {
            if (cid == id) continue
            if (equip && type != null && typeOf(cid) == type) continue
            out += raw
        }
        if (equip) out += LunarWire.equippedCosmetic(id)
        return out
    }

    /** The locker put on or took off [id]: if your account owns it, the same on your Lunar outfit. Game thread. */
    fun sync(id: Int, equip: Boolean) {
        if (!enabled || !known || LunarLocker.kind(id) != LunarLocker.Kind.OWNED) return
        val index = LunarAssets.index ?: return
        val name = index[id]?.name ?: "#$id"
        val o = current()
        if (o == null) {
            if (!equip) return
            create(id, name)
            return
        }
        val list = plan(o.cosmetics, { index[it]?.type }, id, equip)
        val updated = LunarWire.outfit(LunarWire.withCosmetics(o, list))
        // Already as asked: nothing to send.
        if (updated.cosmetics.map { it.first }.sorted() == o.cosmetics.map { it.first }.sorted()) return
        // Taken as done at once, so changes sent one after another (Take all off) build on each other.
        outfits = outfits.map { if (it.id == o.id) updated else it }
        val sent = LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "UpdateOutfit", LunarWire.updateOutfitRequest(updated.raw)) { reply ->
            val (status, saved) = LunarWire.outfitResponse(reply)
            if (status == 1) {
                if (saved != null) outfits = outfits.map { if (it.id == o.id) saved else it }
                tell(if (equip) "put $name on your Lunar outfit \"${o.name}\"" else "took $name off your Lunar outfit")
            } else tell("Lunar didn't update your outfit (status $status)")
        }
        if (!sent) {
            outfits = outfits.map { if (it.id == o.id) o else it }
            tell("not connected to Lunar: $name is only shown here")
        }
    }

    /** No outfit yet: make one wearing [id] and select it. */
    private fun create(id: Int, name: String) {
        LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "CreateOutfit", LunarWire.createOutfitRequest("Horizon")) { reply ->
            val (status, made) = LunarWire.outfitResponse(reply)
            val o = made
            if (status != 1 || o?.id == null) {
                tell("Lunar didn't make an outfit (status $status)")
                return@request
            }
            outfits = outfits + o
            val updated = LunarWire.withCosmetics(o, listOf(LunarWire.equippedCosmetic(id)))
            LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "UpdateOutfit", LunarWire.updateOutfitRequest(updated)) { r ->
                val (s, saved) = LunarWire.outfitResponse(r)
                if (s == 1) outfits = outfits.map { if (it.id == o.id) saved ?: LunarWire.outfit(updated) else it }
            }
            val newTree = LunarWire.treeWithDefault(tree, o.id)
            LunarNetwork.request(LunarWire.COSMETIC_SERVICE, "SelectOutfit", LunarWire.selectOutfitRequest(newTree)) { r ->
                if (LunarWire.outfitResponse(r).first == 1) {
                    tree = newTree
                    tell("put $name on a new Lunar outfit \"Horizon\"")
                } else tell("Lunar didn't select the new outfit")
            }
        }
    }

    private fun tell(text: String) {
        HorizonClient.LOG.info("Lunar locker: {}", text)
        mc.execute { LunarSettings.tell(text) }
    }
}
