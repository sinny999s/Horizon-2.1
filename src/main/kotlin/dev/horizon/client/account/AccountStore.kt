package dev.horizon.client.account

import dev.horizon.client.HorizonClient
import dev.horizon.client.config.Storage
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/**
 * The accounts signed in from the game, kept for every Horizon install of this computer's user: `accounts.hzn` in
 * `~/.horizonclient` (the Horizon Client launcher's folder), so an account added in one instance is there in the others.
 * An account is its Minecraft name and id, its skin, the Microsoft refresh token that signs it in again, and the last
 * Minecraft token with when it runs out (a day), so switching back and forth doesn't sign in each time.
 *
 * The two tokens are sealed ([Secrets]) before they're written. Only the game thread's account worker touches this.
 */
object AccountStore : Accounts.Keeper {
    @Serializable
    data class Saved(
        val uuid: String,
        var name: String,
        var skin: String = "",
        var slim: Boolean = false,
        /** Sealed: the Microsoft refresh token. */
        var refresh: String = "",
        /** Sealed: the last Minecraft token, good until [tokenExpires]. */
        var token: String = "",
        var tokenExpires: Long = 0L,
        /** Microsoft turned the saved sign-in down: it needs signing in again. */
        var signedOut: Boolean = false,
    ) {
        val id: UUID? get() = MicrosoftAuth.uuid(uuid)
    }

    @Serializable
    private data class Data(val accounts: MutableList<Saved> = mutableListOf())

    /** Where the accounts are kept. `HORIZON_ACCOUNTS_FILE` moves it (a portable install, or a test). */
    val file: Path by lazy {
        System.getenv("HORIZON_ACCOUNTS_FILE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".horizonclient", "accounts.hzn")
    }

    private var data: Data? = null

    private fun loaded(): Data = data ?: read().also { data = it }

    /** A file that can't be read is treated as no accounts. Its text isn't logged: a parse error quotes what it read. */
    private fun read(): Data {
        if (!Files.exists(file)) return Data()
        return try {
            Storage.json.decodeFromString(Data.serializer(), Files.readString(file))
        } catch (e: Exception) {
            HorizonClient.LOG.warn("Couldn't read the accounts ({})", e.javaClass.simpleName)
            // Kept aside rather than written over by the next save.
            try {
                Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
            }
            Data()
        }
    }

    @Synchronized
    fun all(): List<Saved> = loaded().accounts.toList()

    @Synchronized
    fun find(uuid: UUID): Saved? = loaded().accounts.firstOrNull { it.id == uuid }

    /** Adds [profile] with its [refreshToken], or brings the saved account up to date; its place in the list is kept. */
    @Synchronized
    override fun put(profile: MicrosoftAuth.Profile, refreshToken: String): Saved {
        val list = loaded().accounts
        val saved = list.firstOrNull { it.id == profile.uuid } ?: Saved(profile.uuid.toString(), profile.name).also { list += it }
        saved.name = profile.name
        saved.skin = profile.skin
        saved.slim = profile.slim
        saved.refresh = Secrets.seal(refreshToken)
        saved.token = Secrets.seal(profile.accessToken)
        saved.tokenExpires = profile.expiresAt
        saved.signedOut = false
        save()
        return saved
    }

    @Synchronized
    override fun refreshed(uuid: UUID, refreshToken: String) {
        val saved = loaded().accounts.firstOrNull { it.id == uuid } ?: return
        saved.refresh = Secrets.seal(refreshToken)
        save()
    }

    /** Marks [uuid]'s saved sign-in as turned down by Microsoft, and forgets its tokens. */
    @Synchronized
    override fun signedOut(uuid: UUID) {
        val saved = loaded().accounts.firstOrNull { it.id == uuid } ?: return
        saved.signedOut = true
        saved.refresh = ""
        saved.token = ""
        saved.tokenExpires = 0L
        save()
    }

    @Synchronized
    fun remove(uuid: UUID) {
        if (loaded().accounts.removeAll { it.id == uuid }) save()
    }

    /** Written to a file beside it first, then moved over: a crash mid-write can't leave half a file. */
    private fun save() {
        val d = data ?: return
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, Storage.json.encodeToString(Data.serializer(), d))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Only the kind of error: a message could carry the path, never a token, but nothing here is worth more.
            HorizonClient.LOG.warn("Couldn't save the accounts ({})", e.javaClass.simpleName)
        }
    }
}

/**
 * Tokens as they are written to disk. On Windows they're encrypted with the Windows user's own key (DPAPI, as
 * browsers and the launcher do): only this Windows user on this computer can read them back. Elsewhere there is no
 * such key without asking for a password, so they're only encoded, as Minecraft's own launcher keeps them.
 */
internal object Secrets {
    private const val DPAPI = "dpapi:"
    private const val PLAIN = "plain:"
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun seal(text: String): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (windows) try {
            return DPAPI + Base64.getEncoder().encodeToString(com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(bytes))
        } catch (_: Throwable) {
            // No DPAPI after all (JNA missing or refused): kept as on other systems.
        }
        return PLAIN + Base64.getEncoder().encodeToString(bytes)
    }

    /** What [sealed] holds, or null when it can't be read here (another user's, another computer's, or damaged). */
    fun open(sealed: String): String? = try {
        when {
            sealed.startsWith(DPAPI) -> String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(sealed.substring(DPAPI.length))), StandardCharsets.UTF_8)
            sealed.startsWith(PLAIN) -> String(Base64.getDecoder().decode(sealed.substring(PLAIN.length)), StandardCharsets.UTF_8)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
