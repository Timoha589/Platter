package com.cappielloantonio.tempo.util

import okhttp3.HttpUrl

object NetworkAddress {
    /**
     * Whether a password sent to [url] cannot be read on the way: the
     * connection is encrypted, or it never leaves this network.
     */
    @JvmStatic
    fun isPrivateOrEncrypted(url: HttpUrl): Boolean {
        if (url.isHttps) return true

        val host = url.host.lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true

        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }
}
