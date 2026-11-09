package dev.horizon.client.module.misc

import dev.horizon.client.config.Friends
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module

/**
 * Replaces names in all drawn text (vanilla's, through StringDecomposerMixin, and the
 * client's own font) with [nameSetting]: our own name always, plus friends' or every tab-list name.
 */
class NameProtect : Module("name-protect", Categories.MISC) {
    enum class Replace { ONLY_SELF, FRIENDS, ALL }

    val nameSetting = string("name", "Horizon")
    val replace = enum("replace", Replace.FRIENDS)

    init {
        instance = this
    }

    /** Our name first, then each tab-list entry in its order, every pass on the previous one's output. */
    fun protectText(text: String): String {
        val p = mc.player ?: return text
        if (mc.level == null || !enabled) return text
        var out = text.replace(p.gameProfile.name, nameSetting.value)
        if (replace.value != Replace.ONLY_SELF) {
            for (info in mc.connection!!.onlinePlayers) {
                val n = info.profile.name
                if (replace.value == Replace.ALL || Friends.isFriend(n)) out = out.replace(n, nameSetting.value)
            }
        }
        return out
    }

    companion object {
        private var instance: NameProtect? = null

        /** For the text hooks; unchanged before the module exists. (An Apollo server's nick for you first.) */
        @JvmStatic
        fun protect(text: String): String {
            val t = dev.horizon.client.lunar.ApolloFeatures.hideNick(text)
            return instance?.protectText(t) ?: t
        }
    }
}
