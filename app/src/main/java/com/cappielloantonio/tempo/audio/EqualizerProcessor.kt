package com.cappielloantonio.tempo.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.platter.automix.transitionBandGain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The app's own equalizer, run over a player's decoded sound: a preamp and ten peaking filters at the desktop's band
 * centres, set from [PlatterEqualizer]. The system's equalizer used to stand in for it, which is a different panel
 * on every phone - and missing on many - and never had the desktop's bands or presets.
 *
 * Each player gets its own: the filters remember the last samples of the sound they are bending.
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {
    @Volatile
    internal var mixBassGain: Float? = null
    @Volatile
    internal var mixDamping: Float? = null

    // Worked out on the playback thread whenever the settings' version moves on.
    private var version = -1
    private var appliedBassGain: Float? = null
    private var appliedDamping: Float? = null
    private var active = false
    private var preamp = 1f
    private var bandCount = 0
    private var bandIndices: List<Int> = emptyList()
    private var b0 = FloatArray(0)
    private var b1 = FloatArray(0)
    private var b2 = FloatArray(0)
    private var a1 = FloatArray(0)
    private var a2 = FloatArray(0)

    // Filter memory per band and channel: [band * channels + channel].
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 16-bit and float are what the decoders hand over; anything else goes past untouched.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        refresh()

        val output = replaceOutputBuffer(size)
        if (!active) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        // Samples are in the machine's order whatever the buffer says; a codec's buffer can say big-endian.
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            var channel = 0
            while (inputBuffer.remaining() >= 2) {
                val y = filter(inputBuffer.short / 32768f, channel, channels)
                output.putShort((y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                channel = if (channel + 1 == channels) 0 else channel + 1
            }
        } else {
            var channel = 0
            while (inputBuffer.remaining() >= 4) {
                val y = filter(inputBuffer.float, channel, channels)
                output.putFloat(y.coerceIn(-1f, 1f))
                channel = if (channel + 1 == channels) 0 else channel + 1
            }
        }
        // A stray byte of a broken frame would be lost otherwise.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    /** One sample through the preamp and every band that is not level (transposed direct form II). */
    private fun filter(sample: Float, channel: Int, channels: Int): Float {
        var x = sample * preamp
        for (band in 0 until bandCount) {
            val i = band * channels + channel
            val y = b0[band] * x + z1[i]
            z1[i] = b1[band] * x - a1[band] * y + z2[i]
            z2[i] = b2[band] * x - a2[band] * y
            x = y
        }
        return x
    }

    /** Works the filters out again when the listener has moved something since the last buffer. */
    private fun refresh() {
        val now = PlatterEqualizer.version
        val bass = mixBassGain
        val damping = mixDamping
        if (now == version && bass == appliedBassGain && damping == appliedDamping) return
        version = now
        appliedBassGain = bass
        appliedDamping = damping

        val settings = PlatterEqualizer.settings
        if (!settings.enabled && bass == null && damping == null) {
            active = false
            return
        }
        val rate = inputAudioFormat.sampleRate.toFloat()
        val channels = inputAudioFormat.channelCount
        val gains = settings.gains().mapIndexed { band, gain ->
            val base = if (settings.enabled) gain else 0f
            transitionBandGain(base, band, bass ?: 1f, damping ?: 0f)
        }

        // Keep the transition bands in place so their filter memory survives gain changes.
        val bands = gains.indices.filter {
            (abs(gains[it]) >= 0.05f || bass != null && it < 2 || damping != null && it >= 5) &&
                EqSettings.FREQUENCIES[it] < rate * 0.45f
        }
        val preampDb = if (settings.enabled) settings.preamp else 0f
        active = bands.isNotEmpty() || abs(preampDb) >= 0.05f
        preamp = 10f.pow(preampDb / 20f)

        val sameBands = bands == bandIndices
        bandIndices = bands
        bandCount = bands.size
        if (b0.size != bandCount) {
            b0 = FloatArray(bandCount); b1 = FloatArray(bandCount); b2 = FloatArray(bandCount)
            a1 = FloatArray(bandCount); a2 = FloatArray(bandCount)
        }
        bands.forEachIndexed { slot, band ->
            // A peaking filter (Robert Bristow-Johnson's cookbook), about an octave wide.
            val a = 10.0.pow(gains[band] / 40.0)
            val w0 = 2 * PI * EqSettings.FREQUENCIES[band] / rate
            val alpha = sin(w0) / (2 * Q)
            val norm = 1 + alpha / a
            b0[slot] = ((1 + alpha * a) / norm).toFloat()
            b1[slot] = (-2 * cos(w0) / norm).toFloat()
            b2[slot] = ((1 - alpha * a) / norm).toFloat()
            a1[slot] = b1[slot]
            a2[slot] = ((1 - alpha / a) / norm).toFloat()
        }
        // Moving a slider keeps the memory, so the sound bends without a click; a band coming or going starts afresh.
        if (!sameBands || z1.size != bandCount * channels) {
            z1 = FloatArray(bandCount * channels)
            z2 = FloatArray(bandCount * channels)
        }
    }

    override fun onFlush() {
        // A new format takes effect here, not in onConfigure: the filters are worked out for it on the next buffer.
        version = -1
        z1.fill(0f)
        z2.fill(0f)
    }

    override fun onReset() {
        version = -1
        bandCount = 0
        bandIndices = emptyList()
        z1 = FloatArray(0)
        z2 = FloatArray(0)
    }

    private companion object {
        const val Q = 1.1
    }
}
