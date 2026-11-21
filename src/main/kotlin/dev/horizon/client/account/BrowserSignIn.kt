package dev.horizon.client.account

import dev.horizon.client.HorizonClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One Microsoft sign-in in the player's own browser. The game never sees a password: it opens Microsoft's page, and
 * listens on this computer only for the browser coming back to `http://localhost:<port>/auth` with a one-time code.
 * Only a request carrying this sign-in's own [state] counts, so nothing else on the computer (or a web page) can
 * finish it with a code of its own.
 */
class BrowserSignIn : AutoCloseable {
    private val verifier = MicrosoftAuth.randomToken(48)
    private val state = MicrosoftAuth.randomToken(24)
    private val listeners = ArrayList<ServerSocket>()
    private val result = CompletableFuture<String>()
    private val port: Int

    /** Where Microsoft sends the browser back to. */
    val redirect: String

    /** Microsoft's sign-in page for this sign-in. */
    val url: String

    init {
        // "localhost" is 127.0.0.1 to some browsers and ::1 to others: listen on both, on the same port.
        val v4 = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        listeners += v4
        port = v4.localPort
        try {
            listeners += ServerSocket(port, 8, InetAddress.getByName("::1"))
        } catch (_: IOException) {
            // No IPv6 here, or the port is taken there: the browser falls back to 127.0.0.1.
        }
        redirect = "http://localhost:$port/auth"
        url = MicrosoftAuth.authorizeUrl(redirect, state, verifier)
        for (l in listeners) Thread({ serve(l) }, "Horizon sign-in").apply { isDaemon = true }.start()
    }

    /** Opens [url] in the browser. */
    fun open() {
        // For tests: the link is written to a file instead of taking over the browser.
        val file = System.getenv("HORIZON_SIGNIN_URL_FILE")
        if (!file.isNullOrBlank()) {
            try {
                Files.writeString(Path.of(file), url)
            } catch (e: IOException) {
                HorizonClient.LOG.warn("Couldn't write the sign-in link to {}", file, e)
            }
            return
        }
        com.mojang.blaze3d.Blaze3D.openUri(java.net.URI(url))
    }

    /**
     * Waits for the browser to come back and signs the account in to Minecraft. [step] is told what is going on, for
     * the screen. Throws [MicrosoftAuth.Failure]; a sign-in nobody finishes ends after [TIMEOUT_MIN] minutes.
     */
    fun await(http: MicrosoftAuth.Transport = MicrosoftAuth.Web, step: (String) -> Unit): Pair<MicrosoftAuth.Profile, String> {
        val code = try {
            result.get(TIMEOUT_MIN, TimeUnit.MINUTES)
        } catch (e: TimeoutException) {
            throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.CANCELLED, "The sign-in wasn't finished in time")
        } catch (e: java.util.concurrent.CancellationException) {
            throw MicrosoftAuth.Failure(MicrosoftAuth.Reason.CANCELLED, "Sign-in cancelled")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? MicrosoftAuth.Failure) ?: MicrosoftAuth.Failure(MicrosoftAuth.Reason.OTHER, "The sign-in failed")
        } finally {
            close()
        }
        step("Signing in to Microsoft")
        val tokens = MicrosoftAuth.redeem(code, redirect, verifier, http)
        step("Signing in to Minecraft")
        return MicrosoftAuth.minecraft(tokens.access, http) to tokens.refresh
    }

    /** Stops listening; a sign-in still being waited for ends as cancelled. */
    override fun close() {
        result.cancel(false)
        for (l in listeners) try {
            l.close()
        } catch (_: IOException) {
        }
    }

    private fun serve(listener: ServerSocket) {
        while (!listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                return
            }
            try {
                socket.use(::answer)
            } catch (_: IOException) {
            }
        }
    }

    private fun answer(socket: Socket) {
        socket.soTimeout = 5000
        val line = readLine(socket) ?: return
        // "GET /auth?code=...&state=... HTTP/1.1"
        val parts = line.split(' ')
        val target = parts.getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        if (parts[0] != "GET" || path != "/auth" || query["state"] != state) {
            respond(socket, 404, page("Nothing here", "This address only finishes a sign-in started from Horizon."))
            return
        }
        val code = query["code"]
        if (code.isNullOrEmpty()) {
            val declined = query["error"] == "access_denied"
            respond(socket, 200, page(if (declined) "Sign-in cancelled" else "Sign-in failed", "You can close this tab and go back to Minecraft."))
            result.completeExceptionally(MicrosoftAuth.Failure(
                if (declined) MicrosoftAuth.Reason.CANCELLED else MicrosoftAuth.Reason.OTHER,
                if (declined) "Sign-in cancelled" else "Microsoft's sign-in failed (${query["error"] ?: "no code"})"))
            return
        }
        respond(socket, 200, page("You're signed in", "You can close this tab and go back to Minecraft."))
        result.complete(code)
    }

    /** The request's first line (the rest isn't needed), or null for a request that isn't one. */
    private fun readLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val out = StringBuilder()
        while (out.length < 8192) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private companion object {
        const val TIMEOUT_MIN = 10L

        /** The page the browser ends on, in the menus' greys. */
        fun page(title: String, text: String) = """<!doctype html><html><head><meta charset="utf-8"><title>Horizon</title>
<style>html,body{height:100%;margin:0}body{display:flex;align-items:center;justify-content:center;background:#0c0c0e;color:#f5f5f7;
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Inter,sans-serif}main{text-align:center}h1{font-size:22px;font-weight:600;
letter-spacing:-.3px;margin:0 0 8px}p{margin:0;font-size:14px;color:rgba(235,235,245,.6)}small{display:block;margin-bottom:22px;
font-size:11px;font-weight:700;letter-spacing:2.4px;color:rgba(235,235,245,.3)}</style></head>
<body><main><small>HORIZON</small><h1>$title</h1><p>$text</p></main></body></html>"""
    }
}
