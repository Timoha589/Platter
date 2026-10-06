package com.platter.desktop.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Which of a server's two addresses to use: the local one while it answers, the main one otherwise - the Android
 * app's `ServerAddress`. The local address is optional; without one there is nothing to choose between and nothing
 * is probed.
 */
object ServerAddress {
    /*
     * Short timeouts: this only asks whether the local address is there. At home it answers in milliseconds; away,
     * an address on someone else's network (or none) must not hold things up for the twenty seconds the Subsonic
     * client would wait.
     */
    private val probe: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Whether a Subsonic server answers at [address]. An unsigned ping is refused, but refused in Subsonic's words;
     * anything else at that address - a router on another network that happens to use the same numbers - does not count.
     */
    suspend fun answers(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Auth.restBase(address) + "ping.view?f=json").build()
            probe.newCall(request).execute().use { it.isSuccessful && it.body?.string()?.contains("subsonic-response") == true }
        } catch (e: IOException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** The address to use: [local] while it answers, [main] otherwise. */
    suspend fun choose(main: String, local: String?): String =
        if (!local.isNullOrBlank() && answers(local)) local else main

    /** Two spellings of one address - `host:4533`, `https://host:4533/` - are the same. */
    fun same(a: String?, b: String?): Boolean =
        a != null && b != null && Auth.restBase(a).lowercase() == Auth.restBase(b).lowercase()
}
