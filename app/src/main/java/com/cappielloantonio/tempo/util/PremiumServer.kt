package com.cappielloantonio.tempo.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Timoha Premium: the Navidrome server the app offers from the first launch,
 * so that signing in takes a username and a password and nothing else.
 *
 * It is also the only server that runs the My Wave service (and the lyrics
 * index next to it), so the wave is offered only while signed in there.
 */
object PremiumServer {
    const val NAME = "Timoha Premium"
    const val HOST = "music.timoha.top"
    const val ADDRESS = "https://$HOST"

    /** Whether [address] - with or without a scheme, any path - is this server. */
    @JvmStatic
    fun isPremium(address: String?): Boolean {
        val trimmed = address?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val url = (if (trimmed.contains("://")) trimmed else "https://$trimmed").toHttpUrlOrNull()
        return url?.host.equals(HOST, ignoreCase = true)
    }

    /** Whether the server signed in to now is this one - the condition for My Wave. */
    @JvmStatic
    fun isInUse(): Boolean = isPremium(Preferences.getServer())
}
