package com.cappielloantonio.tempo.audio

import android.os.Handler
import android.os.Looper
import com.cappielloantonio.tempo.App
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The equalizer as the listener left it: on or off, a preamp, and a gain in dB for each of ten bands. The same
 * bands, range and presets as Platter for Windows, so a sound shaped on one is shaped the same way on the other.
 * An empty [bands] is flat.
 */
data class EqSettings(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val bands: List<Float> = emptyList(),
) {
    /** Always ten values, whatever was saved. */
    fun gains(): List<Float> = List(BANDS) { bands.getOrNull(it) ?: 0f }

    /** Every gain inside the range a slider can ask for. */
    fun clamped(): EqSettings = copy(
        preamp = preamp.coerceIn(-RANGE_DB, RANGE_DB),
        bands = gains().map { it.coerceIn(-RANGE_DB, RANGE_DB) },
    )

    companion object {
        const val BANDS = 10

        /** The most a slider can ask for either way. */
        const val RANGE_DB = 12f

        /** Band centres in Hz, the desktop's (libvlc's), so presets mean the same on both. */
        val FREQUENCIES = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)

        /** The same, for labels. */
        val LABELS = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k")
    }
}

/** Starting points to bend from: ten gains in dB, low to high. Names are keys for strings, see [EqPresets.nameRes]. */
object EqPresets {
    val all: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "flat" to List(EqSettings.BANDS) { 0f },
        "bass_boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "treble_boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 6f),
        "rock" to listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "pop" to listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, -2f, -2f),
        "jazz" to listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f),
        "classical" to listOf(5f, 4f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f),
        "vocal" to listOf(-3f, -2f, -1f, 1f, 4f, 4f, 3f, 1f, 0f, -1f),
        "electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f),
    )

    /** The preset these gains are, if they are one - so the sheet can show which is chosen. */
    fun nameOf(gains: List<Float>): String? = all.entries.firstOrNull { (_, preset) ->
        preset.size == gains.size && preset.zip(gains).all { (a, b) -> abs(a - b) < 0.05f }
    }?.key
}

/**
 * The one equalizer of the app. The players' [EqualizerProcessor]s read it on the playback thread, and the sheet
 * and the player's buttons are told on the main thread whenever it changes; it is kept in the preferences.
 */
object PlatterEqualizer {
    private const val ENABLED = "eq_enabled"
    private const val PREAMP = "eq_preamp"
    private const val BANDS = "eq_bands"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun interface Listener {
        fun onChanged(settings: EqSettings)
    }

    @Volatile
    private var current: EqSettings? = null

    /** Raised on every change, so a processor knows to work its filters out again. */
    @Volatile
    var version = 0
        private set

    @JvmStatic
    val settings: EqSettings
        get() = current ?: load().also { current = it }

    @JvmStatic
    val isEnabled: Boolean
        get() = settings.enabled

    /** Heard at once, and as it was left the next time the app starts. */
    @JvmStatic
    fun set(value: EqSettings) {
        val clamped = value.clamped()
        current = clamped
        version++
        App.getInstance().preferences.edit()
            .putBoolean(ENABLED, clamped.enabled)
            .putFloat(PREAMP, clamped.preamp)
            .putString(BANDS, clamped.bands.joinToString(","))
            .apply()
        main.post { listeners.forEach { it.onChanged(clamped) } }
    }

    @JvmStatic
    fun setEnabled(on: Boolean) = set(settings.copy(enabled = on))

    /** One of [EqPresets]; choosing it also switches the equalizer on, since that is plainly what was meant. */
    @JvmStatic
    fun choosePreset(name: String) {
        val gains = EqPresets.all[name] ?: return
        set(settings.copy(enabled = true, bands = gains))
    }

    /** Levels the bands and the preamp; on stays on. */
    @JvmStatic
    fun reset() = set(EqSettings(enabled = settings.enabled))

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun load(): EqSettings {
        val prefs = App.getInstance().preferences
        val bands = prefs.getString(BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            .orEmpty()
        return EqSettings(prefs.getBoolean(ENABLED, false), prefs.getFloat(PREAMP, 0f), bands).clamped()
    }
}
