package com.nothingplayer.app.expect.ui

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the app's newly-created sign-in profile is accessed, never a normal browser profile. */
internal class DesktopBrowserLogin private constructor(private val profile: Path, private var process: Process, private val browser: File) : AutoCloseable {
    private var endpoint: URI? = null
    private val closed = AtomicBoolean()

    private fun endpoint(): URI {
        endpoint?.let { return it }
        val portFile = profile.resolve("DevToolsActivePort")
        repeat(100) {
            if (Files.isRegularFile(portFile)) {
                val lines = Files.readAllLines(portFile)
                val port = lines.getOrNull(0)?.toIntOrNull()
                val path = lines.getOrNull(1)
                if (port != null && port in 1..65535 && path?.startsWith("/devtools/browser/") == true) {
                    return URI("ws", null, "127.0.0.1", port, path, null, null).also { endpoint = it }
                }
            }
            Thread.sleep(100)
        }
        error("The sign-in browser did not start. Please close it and try again.")
    }

    private fun command(method: String): JsonObject {
        val response = CompletableFuture<JsonObject>()
        val text = StringBuilder()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
        val socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4))
            .buildAsync(endpoint(), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    text.append(data)
                    if (last) {
                        runCatching { Json.parseToJsonElement(text.toString()).jsonObject }.onSuccess {
                            if (it["id"]?.jsonPrimitive?.intOrNull == 1) response.complete(it)
                        }.onFailure(response::completeExceptionally)
                        text.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
                override fun onError(webSocket: WebSocket, error: Throwable) { response.completeExceptionally(error) }
                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    response.completeExceptionally(IllegalStateException("The sign-in browser was closed. Please open it again."))
                    return null
                }
            }).get(5, TimeUnit.SECONDS)
        try {
            socket.sendText(buildJsonObject { put("id", 1); put("method", method) }.toString(), true).get(4, TimeUnit.SECONDS)
            val result = response.get(8, TimeUnit.SECONDS)
            check(result["error"] == null) { "Could not read the sign-in session. Please try again." }
            return result["result"]?.jsonObject ?: buildJsonObject {}
        } finally { socket.abort() }
    }

    fun readCookies(): DesktopLoginCookies {
        // Google sign-in happens only in a regular, non-debugged browser. Once
        // the user closes that window, import its app-owned profile locally.
        if (process.isAlive) throw DesktopSignInWindowStillOpen()
        process = launchBrowser(browser, profile, importing = true)
        endpoint = null
        try {
            return decodeYouTubeLoginCookies(command("Storage.getCookies"))
        } finally {
            runCatching { command("Browser.close") }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
            endpoint = null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (endpoint != null) runCatching { command("Browser.close") }
        if (process.isAlive) process.destroy()
        // The profile is newly created in our temp directory. Cleanup never follows links.
        val owned = profile.toAbsolutePath().normalize()
        val temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        if (owned.parent == temp && owned.fileName.toString().startsWith("nothing-player-signin-")) {
            runCatching { Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } } }
        }
    }

    companion object {
        fun open(): DesktopBrowserLogin {
            val candidates = listOfNotNull(
                System.getenv("ProgramFiles")?.let { "$it/Google/Chrome/Application/chrome.exe" },
                System.getenv("LOCALAPPDATA")?.let { "$it/Google/Chrome/Application/chrome.exe" },
                System.getenv("ProgramFiles")?.let { "$it/BraveSoftware/Brave-Browser/Application/brave.exe" },
                System.getenv("ProgramFiles(x86)")?.let { "$it/Microsoft/Edge/Application/msedge.exe" },
            )
            val browser = candidates.map(::File).firstOrNull(File::isFile)
                ?: error("Install Microsoft Edge or Google Chrome to sign in on Windows.")
            val profile = Files.createTempDirectory("nothing-player-signin-")
            val process = launchBrowser(browser, profile, importing = false)
            return DesktopBrowserLogin(profile, process, browser)
        }

        private fun launchBrowser(browser: File, profile: Path, importing: Boolean): Process =
            ProcessBuilder(listOf(browser.absolutePath) + desktopBrowserArguments(profile, importing))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
}

internal class DesktopSignInWindowStillOpen : IllegalStateException()

internal fun desktopBrowserArguments(profile: Path, importing: Boolean): List<String> =
    listOf("--user-data-dir=$profile", "--no-first-run", "--no-default-browser-check", "--disable-background-mode") +
        if (importing) listOf("--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1", "about:blank")
        else listOf("--new-window", "https://music.youtube.com/")

internal class DesktopLoginCookies(val header: String, val netscape: String) {
    override fun toString(): String = "DesktopLoginCookies(private session)"
}

internal object DesktopCapturedCookies {
    @Volatile var value: DesktopLoginCookies? = null
}

internal fun decodeYouTubeLoginCookies(result: JsonObject): DesktopLoginCookies {
    val now = System.currentTimeMillis() / 1000
    val cookies = result["cookies"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { cookie ->
        val domain = cookie["domain"]?.jsonPrimitive?.content.orEmpty().removePrefix(".").lowercase()
        val name = cookie["name"]?.jsonPrimitive?.content.orEmpty()
        val value = cookie["value"]?.jsonPrimitive?.content.orEmpty()
        val expires = cookie["expires"]?.jsonPrimitive?.doubleOrNull ?: -1.0
        (domain == "youtube.com" || domain.endsWith(".youtube.com")) &&
            name.isNotBlank() && name.none { it in ";=\r\n\t" } && value.none { it in ";\r\n\t" } &&
            (expires <= 0 || expires > now)
    }
    val musicCookies = cookies.filter {
        val domain = it.getValue("domain").jsonPrimitive.content.removePrefix(".").lowercase()
        domain == "youtube.com" || domain == "music.youtube.com"
    }.sortedByDescending { it.getValue("domain").jsonPrimitive.content.length }.distinctBy { it.getValue("name").jsonPrimitive.content }
    check(musicCookies.any { it.getValue("name").jsonPrimitive.content in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") &&
        it.getValue("value").jsonPrimitive.content.isNotBlank() }) {
        "Finish signing in to YouTube Music in the browser first, then choose Finish sign-in."
    }
    val header = musicCookies.joinToString("; ") { "${it.getValue("name").jsonPrimitive.content}=${it.getValue("value").jsonPrimitive.content}" }
    val netscape = "# Netscape HTTP Cookie File\n" + cookies.joinToString("\n", postfix = "\n") {
        val domain = it.getValue("domain").jsonPrimitive.content
        listOf(domain, if (domain.startsWith('.')) "TRUE" else "FALSE", it["path"]?.jsonPrimitive?.content ?: "/",
            if (it["secure"]?.jsonPrimitive?.booleanOrNull == true) "TRUE" else "FALSE",
            (it["expires"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toLong().coerceAtLeast(0).toString(),
            it.getValue("name").jsonPrimitive.content, it.getValue("value").jsonPrimitive.content).joinToString("\t")
    }
    return DesktopLoginCookies(header, netscape)
}
