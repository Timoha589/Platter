package com.platter.desktop.player

/**
 * The equalizer as the listener left it: on or off, a preamp, and a gain in dB for each of libvlc's ten bands.
 * An empty [bands] is flat.
 */
data class EqSettings(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val bands: List<Float> = emptyList(),
) {
    /** Always ten values, whatever was saved. */
    fun gains(): List<Float> = List(BANDS) { bands.getOrNull(it) ?: 0f }

    companion object {
        const val BANDS = 10

        /** The most a slider can ask for either way; libvlc itself takes 20. */
        const val RANGE_DB = 12f

        /** libvlc's band centres, in Hz, for the labels. */
        val FREQUENCIES = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k")
    }
}

/** Starting points to bend from: ten gains in dB, low to high. */
object EqPresets {
    val all: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "Flat" to List(EqSettings.BANDS) { 0f },
        "Bass boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 6f),
        "Rock" to listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, -2f, -2f),
        "Jazz" to listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f),
        "Classical" to listOf(5f, 4f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Vocal" to listOf(-3f, -2f, -1f, 1f, 4f, 4f, 3f, 1f, 0f, -1f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f),
    )

    /** The preset these gains are, if they are one - so the dialog can show which is chosen. */
    fun nameOf(gains: List<Float>): String? = all.entries.firstOrNull { (_, preset) -> preset.zip(gains).all { (a, b) -> kotlin.math.abs(a - b) < 0.05f } && preset.size == gains.size }?.key
}
