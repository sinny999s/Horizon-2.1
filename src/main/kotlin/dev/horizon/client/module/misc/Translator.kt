package dev.horizon.client.module.misc

import dev.horizon.client.combat.Misc2Util
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.SendPacketEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.ChatFormatting
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.world.item.Items
import org.apache.commons.lang3.StringEscapeUtils
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Incoming system chat is translated to [yourLanguage]
 * and printed under the original; our chat is held back and sent translated to [outMessages]. Both go through
 * Google Translate's mobile page on a virtual thread.
 *
 * Reading the page always ends in a NullPointerException (on purpose; its loop asserts each line is non-null before
 * testing for the end of the stream), which the tasks swallow: nothing incoming is printed and our cancelled chat is
 * never sent.
 */
class Translator : Module("translator", Categories.MISC) {
    enum class YourLanguage { AM, AR, EU, BN, BG, CA, CHR, HR, CS, DA, NL, EN, ET, FIL, FI, FR, DE, EL, GU, IW, HI, HU, IS, ID, IT, JA, KN, KO, LV, LT, MS, ML, MR, NO, PL, RO, RU, SR, SK, SL, ES, SW, SV, TA, TE, TH, TR, UR, UK, VI, CY, CN }

    val yourLanguage = enum("your-language", YourLanguage.RU)
    val outMessages = enum("out-messages", YourLanguage.UK)

    init {
        on<ReceivePacketEvent> { e ->
            Misc2Util.received(e) { p ->
                if (p is ClientboundSystemChatPacket) incoming(p.content().string)
                false
            }
        }
        on<SendPacketEvent> { e ->
            if (mc.player == null) return@on
            val p = e.packet as? ServerboundChatPacket ?: return@on
            if (p.message() == lastSent) return@on
            if (mc.player!!.mainHandItem.item == Items.FILLED_MAP || mc.player!!.offhandItem.item == Items.FILLED_MAP) return@on
            if (p.message().startsWith("/")) return@on
            executor.execute { outgoing(p.message()) }
            e.cancel()
        }
    }

    private fun incoming(text: String) {
        for (s in IGNORED) if (text.contains(s)) return
        executor.execute {
            try {
                Misc2Util.chat(this, ChatFormatting.WHITE.toString() + translate(text, yourLanguage.value.name.lowercase(Locale.ROOT)))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** The message sent as translated (it's let through as [lastSent]). */
    private fun outgoing(message: String) {
        try {
            val translated = translate(message, outMessages.value.name.lowercase(Locale.ROOT))
            lastSent = translated
            mc.connection!!.sendChat(translated)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun translate(text: String, lang: String): String {
        val page = StringBuilder()
        val uri = URI.create(String.format(URL, lang, URLEncoder.encode(text.trim { it <= ' ' }, "UTF-8")))
        try {
            BufferedReader(InputStreamReader(uri.toURL().openStream(), "UTF-8")).use { r ->
                while (true) {
                    val line: String = r.readLine()!!
                    page.append(line + "\n")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val m = Pattern.compile("class=\"result-container\">([^<]*)</div>", Pattern.MULTILINE).matcher(page)
        m.find()
        val result = m.group(1)!!
        return if (result.isNotEmpty()) StringEscapeUtils.unescapeHtml4(result) else "translation failed"
    }

    private companion object {
        const val URL = "https://translate.google.com/m?hl=en&sl=auto&tl=%s&ie=UTF-8&prev=_m&q=%s"

        /** System messages that aren't translated. */
        val IGNORED = listOf("was killed by", "was destroyed by", "has joined a game", "has left the game")

        val executor = Executors.newVirtualThreadPerTaskExecutor()

        /** Our last translated message. */
        @Volatile
        var lastSent: String? = null
    }
}
