package com.platter.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The libvlc that goes into the installer is whole enough to play on its own, with no VLC installed or found by any other way. */
class VlcBundleTest {
    private val bundle = File(System.getProperty("platter.test.vlcBundle").orEmpty())

    @Test
    fun `the bundled libvlc plays a file`() {
        // Without a VLC to take the bundle from there is nothing to check.
        if (!File(bundle, "libvlc.dll").isFile) return
        val wav = Files.createTempFile("tone", ".wav").toFile()
        wav.writeBytes(tone())
        val java = File(System.getProperty("java.home"), "bin/java.exe").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.platter.desktop.BundleCheckKt", bundle.path, wav.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the check hung: $output")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.contains("from ${bundle.path}"), "libvlc came from the bundle: $output")
    }

    /** Four seconds of 440 Hz as 16-bit mono WAV. */
    private fun tone(): ByteArray {
        val rate = 44100
        val samples = ByteArray(rate * 2 * 4)
        for (i in 0 until rate * 4) {
            val v = (Math.sin(2 * Math.PI * 440 * i / rate) * 8000).toInt()
            samples[i * 2] = (v and 0xff).toByte()
            samples[i * 2 + 1] = (v shr 8).toByte()
        }
        fun le(n: Int, bytes: Int) = ByteArray(bytes) { ((n shr (8 * it)) and 0xff).toByte() }
        return "RIFF".toByteArray() + le(36 + samples.size, 4) + "WAVEfmt ".toByteArray() + le(16, 4) + le(1, 2) + le(1, 2) +
            le(rate, 4) + le(rate * 2, 4) + le(2, 2) + le(16, 2) + "data".toByteArray() + le(samples.size, 4) + samples
    }
}
