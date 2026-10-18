package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves a player's sound earlier or later by an exact number of samples
 * without stopping it: samples are left out to move it ahead, and silence is
 * put in to hold it back.
 *
 * Seeking would do the same, but a player that is moved by seeking starts
 * again and is heard some tens of milliseconds later - never twice by the
 * same amount - so it cannot be put anywhere to the millisecond. The
 * crossfade's helper is lined up with this while it plays unheard.
 *
 * The player does not know about it: the position it reports is behind where
 * it is heard by [shiftedMs], once the shifted samples have been played.
 */
@UnstableApi
class SampleShifter : BaseAudioProcessor() {
    // Asked for by the main thread and not yet done: samples to leave out (more than zero) or silence to put in (less).
    private val pending = AtomicLong(0)

    // Done on the playback thread: samples left out, less silence put in.
    private val shifted = AtomicLong(0)

    @Volatile
    private var sampleRate = 0

    /* Moves the sound later (less than zero) or earlier by this many milliseconds. */
    fun shift(ms: Long) {
        val rate = sampleRate
        if (rate > 0) pending.addAndGet(ms * rate / 1000)
    }

    /* Whether a shift asked for has not yet gone through. */
    val isShifting: Boolean
        get() = pending.get() != 0L

    /* How far ahead of its reported position the player's sound is, once shifted samples are heard. */
    val shiftedMs: Long
        get() = sampleRate.takeIf { it > 0 }?.let { shifted.get() * 1000 / it } ?: 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Silence is a run of zero bytes in every format but 8-bit, which is left alone.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_8BIT) return AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameSize = inputAudioFormat.bytesPerFrame
        val wanted = pending.get()
        var silence = 0L

        if (wanted > 0) {
            val left = minOf(wanted, (inputBuffer.remaining() / frameSize).toLong())
            inputBuffer.position(inputBuffer.position() + (left * frameSize).toInt())
            pending.addAndGet(-left)
            shifted.addAndGet(left)
        } else if (wanted < 0) {
            silence = -wanted
            pending.addAndGet(silence)
            shifted.addAndGet(-silence)
        }

        val silenceBytes = (silence * frameSize).toInt()
        // Nothing to hand on; and the empty input may be the very buffer an empty output would be.
        if (silenceBytes == 0 && !inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(silenceBytes + inputBuffer.remaining())
        repeat(silenceBytes) { output.put(0) }
        output.put(inputBuffer)
        output.flip()
    }

    // After a seek the player is heard where it says it is again.
    override fun onFlush() {
        pending.set(0)
        shifted.set(0)
    }

    override fun onReset() {
        pending.set(0)
        shifted.set(0)
        sampleRate = 0
    }
}
