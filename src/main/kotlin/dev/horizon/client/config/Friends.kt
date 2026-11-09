package dev.horizon.client.config

import dev.horizon.client.HorizonClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files

/**
 * Friends ("homies"): `homies.hzn`, a JSON array of names, matched exactly and case-sensitively on the
 * profile name. Adding doesn't check for duplicates or blanks.
 */
object Friends {
    val names = ArrayList<String>()

    private val file get() = Storage.root.resolve("homies.hzn")
    private val serializer = ListSerializer(String.serializer())

    /** Also your Hypixel party, while Party Protect is on. */
    fun isFriend(name: String) = names.contains(name) ||
        dev.horizon.client.module.misc.PartyProtect.instance?.covers(name) == true

    fun add(name: String) {
        names += name
    }

    fun remove(name: String) {
        names.remove(name)
    }

    fun load() {
        names.clear()
        try {
            if (Files.exists(file) && Files.size(file) > 0) names.addAll(Storage.json.decodeFromString(serializer, Files.readString(file)))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Homies are not loaded from file for some reasons.")
        }
    }

    fun save() = Storage.write(file, serializer, names.toList())
}
