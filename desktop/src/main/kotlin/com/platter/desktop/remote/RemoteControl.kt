package com.platter.desktop.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.platter.desktop.AppController
import com.platter.desktop.api.SubsonicException
import com.platter.desktop.log.AppLog
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A request that cannot be done as asked: the caller is told [message], with [status]. */
class RemoteError(val status: Int, message: String) : Exception(message)

/**
 * Lets another program on this computer drive the player - Jarvis, the voice assistant, asked to "play Numb" or to
 * skip a song - without anyone clicking on the window.
 *
 * HTTP on 127.0.0.1 only, at [PREFERRED_PORT] or, when that is taken, at any free port. Every start makes a new token;
 * the port and the token are written to `remote.json` beside the settings (`%APPDATA%\Platter`), which only this
 * Windows user can read, and a request without the token is refused. Nothing on the network can reach it, and a web
 * page cannot use it either: it has no way to learn the token.
 *
 * Every request is `POST /v1/<command>` with a JSON object (or nothing) as its body. The answer is JSON: what was asked
 * for, or `{"error": "..."}` with a 4xx or 5xx status. The commands are in [RemoteCommands]. The file goes when Platter
 * closes, so a file that is there but does not answer is left by a Platter that crashed.
 */
class RemoteControl(private val app: AppController, private val file: Path) {
    private var server: HttpServer? = null
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val commands = RemoteCommands(app)
    private val gson = Gson()

    /** The port listened on; null while stopped. */
    val port: Int? get() = server?.address?.port

    fun start() {
        if (server != null) return
        val s = bind() ?: return
        s.createContext("/", ::handle)
        // Two at a time is plenty for one assistant; every command waits for the app's own thread anyway.
        s.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "platter-remote").apply { isDaemon = true } }
        s.start()
        server = s
        try {
            writeFile(s.address.port)
        } catch (e: Exception) {
            AppLog.error("Remote control: could not write ${file.fileName}", e)
        }
        AppLog.info("Remote control listening on 127.0.0.1:${s.address.port}")
    }

    fun stop() {
        val s = server ?: return
        server = null
        s.stop(0)
        (s.executor as? ExecutorService)?.shutdownNow()
        // Only our own file: a second Platter started meanwhile has written its own.
        runCatching { if (Files.readString(file).contains(token)) Files.deleteIfExists(file) }
    }

    private fun bind(): HttpServer? {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in listOf(PREFERRED_PORT, 0)) {
            try {
                return HttpServer.create(InetSocketAddress(loopback, port), 0)
            } catch (e: BindException) {
                // Taken, by another Platter or by something else: any free port will do, the file says which.
            } catch (e: Exception) {
                AppLog.error("Remote control could not start", e)
                return null
            }
        }
        return null
    }

    private fun writeFile(port: Int) {
        Files.createDirectories(file.parent)
        val info = mapOf(
            "port" to port,
            "token" to token,
            "pid" to ProcessHandle.current().pid(),
            "version" to app.version,
            "api" to API_VERSION,
        )
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, gson.toJson(info))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val (status, body) = answer(exchange)
            val bytes = gson.toJson(body).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            AppLog.error("Remote control: could not answer", e)
        } finally {
            exchange.close()
        }
    }

    private fun answer(exchange: HttpExchange): Pair<Int, Any> {
        if (!authorized(exchange.requestHeaders.getFirst("Authorization"))) {
            return 401 to error("Wrong or missing token: it is in ${file.fileName}, as Authorization: Bearer <token>")
        }
        if (exchange.requestMethod != "POST") return 405 to error("Use POST")
        val path = exchange.requestURI.path
        if (!path.startsWith("/v1/")) return 404 to error("Unknown path $path")
        val command = path.removePrefix("/v1/").trim('/')
        val args = try {
            val text = exchange.requestBody.use { String(it.readAllBytes(), Charsets.UTF_8) }
            if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            return 400 to error("The body must be a JSON object")
        }
        return try {
            // On the app's own thread, as a press of a button would be: the screens see the change at once.
            200 to runBlocking {
                withTimeout(COMMAND_TIMEOUT_MS) { app.scope.async { commands.run(command, Args(args)) }.await() }
            }
        } catch (e: RemoteError) {
            e.status to error(e.message.orEmpty())
        } catch (e: TimeoutCancellationException) {
            504 to error("Platter took too long to answer")
        } catch (e: SubsonicException) {
            502 to error("The server: ${e.message}")
        } catch (e: Exception) {
            AppLog.error("Remote command $command failed", e)
            500 to error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun authorized(header: String?): Boolean {
        val given = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun error(message: String) = mapOf("error" to message)

    companion object {
        /** Where it listens if it can; Feishin's remote is on 4333, so this is the one next to it. */
        const val PREFERRED_PORT = 4334

        /** Raised when a command changes or goes, so a caller can tell an old Platter from a new one. */
        const val API_VERSION = 1

        private const val COMMAND_TIMEOUT_MS = 30_000L
    }
}

/** The body of a request, read leniently: a number may come as a string, a flag as "true" or 1. */
class Args(private val o: JsonObject) {
    fun string(name: String): String? =
        o.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    fun double(name: String): Double? = string(name)?.toDoubleOrNull()

    fun int(name: String): Int? = double(name)?.toInt()

    fun bool(name: String): Boolean? = string(name)?.lowercase()?.let { it in setOf("true", "1", "yes", "on") }

    /** A list of strings; a single string counts as a list of one. */
    fun strings(name: String): List<String> {
        val e = o.get(name) ?: return emptyList()
        return if (e.isJsonArray) e.asJsonArray.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
        else listOfNotNull(string(name))
    }

    fun need(name: String): String = string(name) ?: throw RemoteError(400, "\"$name\" is needed")
}
