package com.platter.desktop

import com.platter.desktop.log.AppLog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogTest {
    @Test
    fun `the token, salt and password of an address are blanked out`() {
        val text = "GET http://h:4533/rest/stream?u=tim&t=0123abcd&s=pepper&id=7 and p=enc:secret"
        val redacted = AppLog.redact(text)
        assertFalse(redacted.contains("0123abcd") || redacted.contains("pepper") || redacted.contains("secret"), redacted)
        assertTrue(redacted.contains("u=tim") && redacted.contains("id=7"), "the rest stays readable: $redacted")
    }

    @Test
    fun `errors, uncaught exceptions and stderr end up in the file`() {
        val folder = Files.createTempDirectory("platter-log-test")
        val java = File(System.getProperty("java.home"), "bin/java.exe").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.platter.desktop.LogCheckKt", folder.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output)
        val log = Files.readString(folder.resolve("platter.log"))
        assertTrue(log.contains("ERROR [main] a handled failure") && log.contains("IllegalStateException: boom"), log)
        assertTrue(log.contains("Uncaught exception in thread worker-x") && log.contains("nobody catches this"), log)
        assertTrue(log.contains("STDERR") && log.contains("something printed to stderr"), log)
        assertFalse(log.contains("0123abcd") || log.contains("pepper"), "no secrets in the log: $log")
        assertEquals(1, log.lines().count { it.contains("started;") }, log)
    }
}
