package com.platter.desktop

import com.platter.desktop.api.Song
import com.platter.desktop.player.EqSettings
import com.platter.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the equalizer does to the sound itself, heard through the real player: libvlc writes what it would play to a
 * WAV file (`--aout=afile`), and the file is measured. libvlc's own equalizer takes 12 dB off at preamp 0, which once
 * made switching it on a quarter as loud; and it filters ahead of what is heard by its caching, which once made a
 * slider heard more than a second after it moved.
 */
class EqualizerSoundTest {
    private val rate = 44100

    /** Eight seconds of three tones, low, middle and high, at a level that leaves room for the filters. */
    private fun tone(file: File) {
        val frames = rate * 8
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val v = 0.15 * (sin(2 * PI * 100 * t) + sin(2 * PI * 1000 * t) + sin(2 * PI * 5000 * t))
            val s = (v * 32767).toInt().toShort()
            data.putShort(s); data.putShort(s)
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + frames * 4); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(frames * 4)
        file.writeBytes(h.array() + data.array())
    }

    /** The left channel's loudness between two moments of the file, in seconds. */
    private fun rms(file: File, from: Double, to: Double): Double {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        var sum = 0.0
        var n = 0
        var i = 0
        while (b.remaining() >= 4) {
            val s = b.short / 32768.0
            b.short
            if ((i++.toDouble() / rate) in from..to) { sum += s * s; n++ }
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    /** Plays the tone through a fresh player with [eq], calls [during] three seconds in, and returns what was written. */
    private fun record(input: File, eq: EqSettings, during: (PlayerController) -> Unit = {}): File {
        val out = File.createTempFile("platter-eq-out", ".wav")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(
            scope, { input.absolutePath }, { _, _, _ -> },
            vlcArgs = listOf("--aout=afile", "--audiofile-file=${out.absolutePath}", "--audiofile-wav", "--audiofile-format=s16", "--audiofile-channels=2"),
            initialEqualizer = eq,
        )
        try {
            player.play(listOf(Song().apply { id = "tone"; title = "Tone"; duration = 8 }), 0)
            waitFor { player.state.value.positionMs >= 3000 }
            during(player)
            waitFor(timeoutMs = 20_000) { player.state.value.positionMs >= 7500 || !player.state.value.isPlaying }
        } finally {
            player.releaseAndWait()
        }
        return out
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out")
    }

    @Test
    fun `switched on and level, the equalizer leaves the music as loud as it was`() {
        val input = File.createTempFile("platter-eq-in", ".wav").also { tone(it) }
        val original = rms(input, 1.0, 2.5)
        val off = rms(record(input, EqSettings(enabled = false)), 1.0, 2.5)
        val level = rms(record(input, EqSettings(enabled = true)), 1.0, 2.5)
        assertTrue(abs(off - original) / original < 0.05, "off is untouched: $off against $original")
        assertTrue(abs(level - off) / off < 0.1, "on and level is as loud as off: $level against $off")
    }

    @Test
    fun `a slider moved while a song plays is heard within a fraction of a second`() {
        val input = File.createTempFile("platter-eq-in", ".wav").also { tone(it) }
        var askedAt = 0.0
        val out = record(input, EqSettings(enabled = true)) { player ->
            askedAt = player.state.value.positionMs / 1000.0
            player.setEqualizer(EqSettings(enabled = true, preamp = -12f))
        }
        val before = rms(out, 1.0, 2.5)
        // The first tenth of a second that is clearly quieter: the moment the change is heard.
        val heard = (0 until 80).map { it / 10.0 }.firstOrNull { it > askedAt - 0.5 && rms(out, it, it + 0.1) < before * 0.5 }
            ?: fail("the change was never heard")
        assertTrue(heard - askedAt < 0.8, "asked for at $askedAt s, heard at $heard s")
    }
}
