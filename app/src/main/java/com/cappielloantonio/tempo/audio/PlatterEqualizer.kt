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

/** A sound the listener shaped and kept under a name of their own: the preamp as well as the ten bands. */
data class SavedEqPreset(val name: String, val preamp: Float, val bands: List<Float>) {
    /** Whether [settings] are this preset, so the sheet can show it chosen. */
    fun matches(settings: EqSettings): Boolean =
        abs(preamp - settings.preamp) < 0.05f &&
            EqSettings(bands = bands).gains().zip(settings.gains()).all { (a, b) -> abs(a - b) < 0.05f }
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
        // Deep low end for the beat, a step back in the low mids, the voice and the hats brought forward.
        "rap" to listOf(6f, 5f, 2f, 0f, -1f, 1f, 2f, 1f, 2f, 2f),
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
    private const val SAVED = "eq_saved_presets"

    /** A preset's name fits on its chip. */
    private const val MAX_PRESET_NAME = 32

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

    /** The listener's own presets, in the order they were made. Kept as JSON, which needs no classes R8 could strip. */
    @JvmStatic
    val savedPresets: List<SavedEqPreset>
        get() = saved ?: loadSaved().also { saved = it }

    @Volatile
    private var saved: List<SavedEqPreset>? = null

    /** Keeps the sound as it is now under [name]; a preset of the same name is replaced, where it was in the list. */
    @JvmStatic
    fun savePreset(name: String) {
        val clean = name.trim().take(MAX_PRESET_NAME)
        if (clean.isEmpty()) return
        val now = settings
        val preset = SavedEqPreset(clean, now.preamp, now.gains())
        val list = savedPresets.toMutableList()
        val at = list.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        if (at >= 0) list[at] = preset else list += preset
        storeSaved(list)
    }

    @JvmStatic
    fun deletePreset(name: String) = storeSaved(savedPresets.filterNot { it.name == name })

    /** One of the listener's own presets: its preamp and its bands, and the equalizer on. */
    @JvmStatic
    fun chooseSavedPreset(preset: SavedEqPreset) =
        set(EqSettings(enabled = true, preamp = preset.preamp, bands = EqSettings(bands = preset.bands).gains()))

    private fun storeSaved(list: List<SavedEqPreset>) {
        saved = list
        val json = org.json.JSONArray()
        list.forEach { preset ->
            json.put(
                org.json.JSONObject()
                    .put("name", preset.name)
                    .put("preamp", preset.preamp.toDouble())
                    .put("bands", org.json.JSONArray(preset.bands.map { it.toDouble() }))
            )
        }
        App.getInstance().preferences.edit().putString(SAVED, json.toString()).apply()
        // The sheet redraws its chips from the same call as for any other change.
        val now = settings
        main.post { listeners.forEach { it.onChanged(now) } }
    }

    private fun loadSaved(): List<SavedEqPreset> = try {
        val json = org.json.JSONArray(App.getInstance().preferences.getString(SAVED, null) ?: "[]")
        (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bands = item.optJSONArray("bands")?.let { array -> (0 until array.length()).map { array.optDouble(it, 0.0).toFloat() } }.orEmpty()
            SavedEqPreset(name, item.optDouble("preamp", 0.0).toFloat(), bands)
        }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

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
