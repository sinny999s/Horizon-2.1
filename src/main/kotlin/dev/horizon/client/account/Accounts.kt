package dev.horizon.client.account

import dev.horizon.client.HorizonClient
import net.minecraft.client.Minecraft
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The accounts the game can play as, for the Accounts screen: the Horizon Client launcher's (when it started the
 * game), the ones signed in from the game ([AccountStore]), and the one the game was started with when it is
 * neither. Switching, signing in and removing run on a worker thread, one at a time; what the screen shows ([entries],
 * [busy], [status], [signIn]) only changes on the game thread.
 */
object Accounts {
    private val mc get() = Minecraft.getInstance()

    enum class Source {
        /** Signed in to the launcher: it keeps the sign-in and renews it. */
        LAUNCHER,
        /** Signed in from the game. */
        SAVED,
        /** Only what the game was started with (or is playing as): nothing to renew it with. */
        SESSION,
    }

    class Entry(val uuid: UUID, val name: String, val source: Source, val skin: String, val launcherId: String?,
                /** Its sign-in was turned down: it needs signing in again (the launcher's: in the launcher) before it can be played as. */
                val signedOut: Boolean, val saved: Boolean)

    /** What the screen's foot says: the last thing that happened, and whether it went well, went wrong, or is just a note. */
    class Status(val text: String, val kind: Kind) {
        enum class Kind { DONE, NOTE, ERROR }
    }

    /** A sign-in under way in the browser. */
    class SignIn internal constructor(internal val browser: BrowserSignIn) {
        val url: String get() = browser.url

        /** What is going on, once the browser has come back. */
        @Volatile
        var step = ""
            internal set

        /** Why it failed, once it has. */
        @Volatile
        var error: String? = null
            internal set
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Horizon accounts").apply { isDaemon = true } }

    @Volatile
    var entries: List<Entry> = emptyList()
        private set

    /** The launcher's accounts as it last listed them (kept when it stops answering). */
    private var launcher: List<LauncherBridge.Account> = emptyList()

    /** The launcher's accounts it couldn't sign in quietly when asked from here: they say so until it lists them again. */
    private val launcherSignedOut = HashSet<UUID>()

    /** Whether the launcher's accounts are being asked for (the list may still grow). */
    @Volatile
    var loading = false
        private set

    /** The account being switched to. */
    @Volatile
    var busy: UUID? = null
        private set

    @Volatile
    var status: Status? = null
        private set

    @Volatile
    var signIn: SignIn? = null
        private set

    /** The account just added or switched to, and when: its row lights up for a moment. */
    @Volatile
    var flash: Pair<UUID, Long>? = null
        private set

    /** The account the game is playing as. */
    val active: UUID get() = mc.user.profileId

    /** Called when the screen opens: what's known is shown at once, and the launcher is asked for its accounts. */
    fun open() {
        status = null
        rebuild()
        if (!LauncherBridge.available || loading) return
        loading = true
        worker.execute {
            val list = try {
                LauncherBridge.accounts()
            } catch (e: MicrosoftAuth.Failure) {
                null
            } catch (e: Exception) {
                HorizonClient.LOG.warn("Couldn't list the launcher's accounts ({})", e.javaClass.simpleName)
                null
            }
            mc.execute {
                loading = false
                if (list != null) {
                    launcher = list
                    launcherSignedOut.clear()
                }
                rebuild()
            }
        }
    }

    /** Puts the list together again (game thread). The order holds still: the launcher's, then the game's own. */
    private fun rebuild() {
        val out = ArrayList<Entry>()
        val saved = AccountStore.all()
        for (a in launcher) out += Entry(a.uuid, a.name, Source.LAUNCHER, a.skin, a.id, a.signedOut || a.uuid in launcherSignedOut, saved.any { it.id == a.uuid })
        for (s in saved) {
            val id = s.id ?: continue
            if (out.none { it.uuid == id }) out += Entry(id, s.name, Source.SAVED, s.skin, null, s.signedOut, true)
        }
        // The account the game was started with, and the one it's playing as, when they are in neither list.
        val launch = GameSession.launch
        val now = mc.user
        val extra = ArrayList<Entry>()
        for (u in listOf(launch, now)) if (out.none { it.uuid == u.profileId } && extra.none { it.uuid == u.profileId })
            extra += Entry(u.profileId, u.name, Source.SESSION, "", null, false, false)
        entries = extra + out
    }

    /** Whether [e] can be switched to right now. */
    fun canSwitch(e: Entry) = busy == null && signIn == null && e.uuid != active && mc.level == null

    /** Plays as [e] from now on. */
    fun switchTo(e: Entry) {
        if (!canSwitch(e)) return
        // An account whose sign-in ran out is signed in again instead (the launcher's: in the launcher).
        if (e.signedOut && e.source == Source.SAVED) return add()
        busy = e.uuid
        status = null
        worker.execute {
            val result = try {
                Result.success(prepare(e))
            } catch (f: MicrosoftAuth.Failure) {
                Result.failure(f)
            } catch (x: Exception) {
                HorizonClient.LOG.warn("Account switch failed ({})", x.javaClass.simpleName)
                Result.failure(MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "Couldn't switch account"))
            }
            mc.execute {
                busy = null
                result.onSuccess { p ->
                    if (mc.level != null) {
                        status = Status("Leave the world first", Status.Kind.ERROR)
                    } else {
                        GameSession.apply(p)
                        launcherSignedOut.remove(e.uuid)
                        flash = p.user.profileId to System.currentTimeMillis()
                        status = Status("Playing as ${p.user.name}", Status.Kind.DONE)
                    }
                }.onFailure { f ->
                    if (e.launcherId != null && (f as? MicrosoftAuth.Failure)?.reason == MicrosoftAuth.Reason.SIGN_IN_AGAIN) launcherSignedOut += e.uuid
                    status = Status(f.message ?: "Couldn't switch account", Status.Kind.ERROR)
                }
                rebuild()
            }
        }
    }

    /** The session for [e]: the launcher's token, the game's own saved sign-in, or the account the game started with. */
    private fun prepare(e: Entry): GameSession.Prepared {
        val launch = GameSession.launch
        if (e.launcherId != null) {
            try {
                val t = LauncherBridge.token(e.launcherId)
                return GameSession.prepare(t.name, t.uuid, t.accessToken)
            } catch (f: MicrosoftAuth.Failure) {
                // The launcher has gone or can't just now: the game's own sign-in for the account, or what it started with.
                val own = e.saved && AccountStore.find(e.uuid)?.signedOut == false
                if (!own && e.uuid != launch.profileId) throw f
            }
        }
        if (e.saved && AccountStore.find(e.uuid)?.signedOut == false) {
            val saved = AccountStore.find(e.uuid) ?: throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "This account was removed")
            val p = SavedSignIn.fresh(saved, MicrosoftAuth.Web, System.currentTimeMillis(), AccountStore)
            return GameSession.prepare(p.name, p.uuid, p.accessToken)
        }
        if (e.uuid == launch.profileId) return GameSession.prepare(launch.name, launch.profileId, launch.accessToken)
        throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.SIGN_IN_AGAIN, "Sign in to ${e.name} again")
    }

    /** Signs another account in, in the browser. */
    fun add() {
        if (signIn != null || busy != null) return
        val browser = try {
            BrowserSignIn()
        } catch (e: Exception) {
            status = Status("Couldn't start a sign-in on this computer", Status.Kind.ERROR)
            return
        }
        val s = SignIn(browser)
        signIn = s
        status = null
        browser.open()
        // Not on the worker: it waits for a person, and cancelling must not queue behind it.
        Thread({
            try {
                val (profile, refresh) = browser.await { step -> s.step = step }
                // Given up meanwhile: the account isn't kept.
                if (signIn !== s) return@Thread
                AccountStore.put(profile, refresh)
                mc.execute {
                    if (signIn === s) signIn = null
                    flash = profile.uuid to System.currentTimeMillis()
                    status = Status("${profile.name} added", Status.Kind.DONE)
                    rebuild()
                }
            } catch (f: MicrosoftAuth.Failure) {
                mc.execute {
                    // Given up from the screen: it has already gone back to the list.
                    if (signIn !== s) return@execute
                    if (f.reason == MicrosoftAuth.Reason.CANCELLED) {
                        // Closed or declined in the browser: back to the list, with a word about it.
                        signIn = null
                        status = Status(f.message ?: "Sign-in cancelled", Status.Kind.NOTE)
                    } else s.error = f.message ?: "The sign-in failed"
                }
            } catch (x: Exception) {
                HorizonClient.LOG.warn("Sign-in failed ({})", x.javaClass.simpleName)
                mc.execute { if (signIn === s) s.error = "The sign-in failed" }
            }
        }, "Horizon sign-in wait").apply { isDaemon = true }.start()
    }

    /** Opens the sign-in page again (the tab was closed, or it never opened). */
    fun reopen() {
        signIn?.browser?.open()
    }

    /** Gives up the sign-in under way, or leaves its error. */
    fun cancelSignIn() {
        val s = signIn ?: return
        signIn = null
        s.browser.close()
    }

    /** Forgets an account signed in from the game. The game goes on playing as it until another is picked. */
    fun remove(e: Entry) {
        if (!e.saved || busy != null) return
        AccountStore.remove(e.uuid)
        status = Status("${e.name} removed", Status.Kind.DONE)
        rebuild()
    }

    /** Whether [e] can be removed here: the launcher's own accounts are removed in the launcher. */
    fun canRemove(e: Entry) = e.saved && e.source == Source.SAVED

    /** What a saved account's sign-in is written back to. */
    interface Keeper {
        /** The account signed in: its profile, Minecraft token and (new) refresh token. */
        fun put(profile: MicrosoftAuth.Profile, refreshToken: String): AccountStore.Saved

        /** Only the refresh token changed. */
        fun refreshed(uuid: UUID, refreshToken: String)

        /** Microsoft turned the saved sign-in down. */
        fun signedOut(uuid: UUID)
    }

    /** The saved-account half of switching, apart from the game and the disk so it can be tested. */
    object SavedSignIn {
        /** A Minecraft token this close to running out isn't handed to the game. */
        const val MARGIN_MS = 10 * 60_000L

        /**
         * A working Minecraft token for [saved]: its last one while that has [MARGIN_MS] left and Minecraft still takes
         * it (asking also brings a changed name or skin in), else a new one from its saved Microsoft sign-in.
         */
        fun fresh(saved: AccountStore.Saved, http: MicrosoftAuth.Transport, now: Long, keep: Keeper): MicrosoftAuth.Profile {
            val uuid = saved.id ?: throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "This account's file is damaged")
            val refresh = saved.refresh.takeIf { it.isNotEmpty() }?.let(Secrets::open)
            val token = saved.token.takeIf { it.isNotEmpty() && saved.tokenExpires - now > MARGIN_MS }?.let(Secrets::open)
            if (!token.isNullOrEmpty()) try {
                val p = MicrosoftAuth.profile(token, saved.tokenExpires, http)
                if (p.uuid == uuid) {
                    if (refresh != null && (p.name != saved.name || p.skin != saved.skin)) keep.put(p, refresh)
                    return p
                }
            } catch (f: MicrosoftAuth.Failure) {
                // Turned down early (a password change signs every token out): a new one is asked for below.
                if (f.reason != MicrosoftAuth.Reason.SIGN_IN_AGAIN) throw f
            }
            if (refresh.isNullOrEmpty()) throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.SIGN_IN_AGAIN, "Sign in to ${saved.name} again")
            val tokens = try {
                MicrosoftAuth.refresh(refresh, http)
            } catch (f: MicrosoftAuth.Failure) {
                if (f.reason != MicrosoftAuth.Reason.SIGN_IN_AGAIN) throw f
                keep.signedOut(uuid)
                throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.SIGN_IN_AGAIN, "Sign in to ${saved.name} again")
            }
            // Microsoft has replaced the refresh token by now: the new one is kept even if Minecraft's part fails.
            keep.refreshed(uuid, tokens.refresh)
            val p = MicrosoftAuth.minecraft(tokens.access, http, now)
            if (p.uuid != uuid) throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "That sign-in is for another account")
            keep.put(p, tokens.refresh)
            return p
        }
    }
}
