package com.platter.automix

import com.google.gson.annotations.SerializedName

internal data class AutoMixResponse(
    @SerializedName("tracks") val tracks: Map<String, TrackStructure>? = null,
    @SerializedName("pending") val pending: List<String>? = null,
)

internal data class TrackStructure(
    @SerializedName("durationMs") val durationMs: Long = 0,
    @SerializedName("beatsMs") val beatsMs: List<Long>? = null,
    @SerializedName("downbeatsMs") val downbeatsMs: List<Long>? = null,
    @SerializedName("sections") val sections: List<TrackSection>? = null,
    @SerializedName("audioMuse") val audioMuse: AudioMuseFeatures? = null,
    @SerializedName("audioLevels") val audioLevels: TrackAudioLevels? = null,
    @SerializedName("beatOffsetsMs") val beatOffsetsMs: List<Long?>? = null,
    @SerializedName("bassEntries") val bassEntries: TrackBassEntries? = null,
)

internal data class TrackBassEntries(
    @SerializedName("version") val version: Int = 0,
    @SerializedName("downbeatsMs") val downbeatsMs: List<Long>? = null,
)

internal data class TrackAudioLevels(
    @SerializedName("startMs") val startMs: Long = 0,
    @SerializedName("stepMs") val stepMs: Long = 0,
    @SerializedName("momentaryLufs") val momentaryLufs: List<Double>? = null,
    @SerializedName("firstSoundMs") val firstSoundMs: Long? = null,
    @SerializedName("lastSoundMs") val lastSoundMs: Long? = null,
)

internal data class AudioMuseFeatures(
    @SerializedName("tempoBpm") val tempoBpm: Double? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("scale") val scale: String? = null,
    @SerializedName("energy") val energy: Double? = null,
)

internal data class TrackSection(
    @SerializedName("startMs") val startMs: Long = 0,
    @SerializedName("endMs") val endMs: Long = 0,
    @SerializedName("label") val label: String? = null,
)
