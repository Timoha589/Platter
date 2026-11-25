package com.platter.desktop.api

import com.platter.desktop.log.AppLog

internal typealias AutoMixResponse = com.platter.automix.AutoMixResponse
internal typealias TrackStructure = com.platter.automix.TrackStructure
internal typealias TrackAudioLevels = com.platter.automix.TrackAudioLevels
internal typealias AudioMuseFeatures = com.platter.automix.AudioMuseFeatures
internal typealias TrackSection = com.platter.automix.TrackSection

/** Direct Navidrome can serve streams while its main proxy provides the analysis route. */
internal class AutoMixSource(private var client: SubsonicClient, private val mainAddress: String?) {
    suspend fun get(ids: List<String>): AutoMixResponse? {
        client.autoMix(ids)?.let { return it }
        val main = mainAddress?.takeIf { !ServerAddress.same(it, client.credentials.serverUrl) } ?: return null
        client = SubsonicClient(client.credentials.copy(serverUrl = main))
        AppLog.info("AutoMix endpoint absent at the home address; requesting analysis from the main address")
        return client.autoMix(ids)
    }
}
