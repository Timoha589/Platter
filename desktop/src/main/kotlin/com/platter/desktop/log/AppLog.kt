package com.platter.desktop.log

import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What went wrong, written to `%APPDATA%\Platter\logs\platter.log` so a listener can send the file when something
 * does not work: an installed app has no console. Errors the program did not catch and anything printed to stderr land
 * here as well. The file is cut at [MAX_BYTES]: the full one is kept as `platter.1.log` and a fresh one begins.
 *
 * Nothing secret is written: the token, salt and password parts of a URL are blanked out.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val secrets = Regex("""([?&;\s](?:p|t|s|password|token|salt|apiKey|api_key)=)[^&\s"']+""", RegexOption.IGNORE_CASE)
    private val lock = Any()

    @Volatile
    private var file: Path? = null

    @Volatile
    private var installed = false

    val folder: Path get() = defaultFolder()

    fun defaultFolder(): Path {
        val base = System.getenv("APPDATA")?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), ".config")
        return base.resolve("Platter").resolve("logs")
    }

    /** The log file, once [install] has run. */
    val path: Path? get() = file

    /**
     * Starts the log in [folder]: opens the file, writes the header, and takes over uncaught exceptions and stderr.
     * Does nothing when the folder cannot be written to - the app must never fail because it cannot log.
     */
    fun install(folder: Path = defaultFolder()) {
        if (installed) return
        try {
            Files.createDirectories(folder)
            file = folder.resolve("platter.log")
            rotateIfLarge(file!!)
        } catch (e: Exception) {
            return
        }
        installed = true
        info(
            "Platter ${System.getProperty("platter.version") ?: "(from sources)"} started; " +
                "Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        )
        Thread.setDefaultUncaughtExceptionHandler { thread, e -> error("Uncaught exception in thread ${thread.name}", e) }
        System.setErr(PrintStream(StderrTee(System.err), true, StandardCharsets.UTF_8))
    }

    /** Arguments that make libvlc write its own errors to `vlc.log` beside the app's. Empty before [install] or when it failed. */
    fun vlcArguments(): List<String> {
        val dir = file?.parent ?: return emptyList()
        val vlcLog = dir.resolve("vlc.log")
        try {
            rotateIfLarge(vlcLog)
        } catch (e: Exception) {
            return emptyList()
        }
        return listOf("--file-logging", "--logfile=$vlcLog", "--log-verbose=0")
    }

    fun info(message: String) = write("INFO ", message, null)

    fun error(message: String, cause: Throwable? = null) = write("ERROR", message, cause)

    private fun write(level: String, message: String, cause: Throwable?) {
        val target = file ?: return
        val text = StringBuilder()
            .append(LocalDateTime.now().format(stamp)).append(' ').append(level).append(" [").append(Thread.currentThread().name).append("] ")
            .append(redact(message)).append('\n')
        if (cause != null) text.append(redact(StringWriter().also { cause.printStackTrace(PrintWriter(it)) }.toString()))
        try {
            synchronized(lock) {
                rotateIfLarge(target)
                Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (e: Exception) {
            // A log that cannot be written is not worth failing over.
        }
    }

    internal fun redact(text: String): String = secrets.replace(text) { it.groupValues[1] + "***" }

    private fun rotateIfLarge(target: Path) {
        if (!Files.exists(target) || Files.size(target) < MAX_BYTES) return
        val name = target.fileName.toString()
        val older = target.resolveSibling(name.substringBeforeLast('.') + ".1." + name.substringAfterLast('.'))
        Files.move(target, older, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Lets stderr through as before and copies each of its lines into the log. */
    private class StderrTee(private val original: PrintStream) : OutputStream() {
        private val line = StringBuilder()

        @Synchronized
        override fun write(b: Int) {
            original.write(b)
            if (b == '\n'.code) flushLine() else if (b != '\r'.code) line.append(b.toChar())
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
            val text = String(b, off, len, StandardCharsets.UTF_8)
            for (c in text) if (c == '\n') flushLine() else if (c != '\r') line.append(c)
        }

        override fun flush() = original.flush()

        private fun flushLine() {
            if (line.isNotEmpty()) AppLog.write("STDERR", line.toString(), null)
            line.setLength(0)
        }
    }
}
