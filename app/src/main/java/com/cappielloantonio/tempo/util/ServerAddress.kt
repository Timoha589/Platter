package com.cappielloantonio.tempo.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cappielloantonio.tempo.App
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which of a server's two addresses to use: the local one while it answers,
 * the main one otherwise.
 *
 * The local address is optional. Without one there is nothing to choose
 * between, nothing is probed, and every address in the app is the main one -
 * exactly as if this did not exist.
 *
 * With one, the choice is made again whenever the phone changes network (the
 * player service keeps the process alive, so this works with the app closed),
 * when the app comes to the front, and when a track fails to load. Leaving
 * home, the phone drops off Wi-Fi, the local address stops answering within a
 * couple of seconds, and the stream carries on over the main address; coming
 * back, it moves to the local one.
 *
 * The choice used to be made only when the app came to the front, by pinging
 * one address and flipping to the other on failure, and it was baked into
 * every track's stream URL as the track was queued: a queue put together at
 * home kept pointing at the home address after leaving. Now URLs are rewritten
 * as each track is opened ([rewrite]), so a queue always follows the address
 * in use.
 */
object ServerAddress {
    private const val TAG = "ServerAddress"

    /* A network change comes in a burst of callbacks; decide once it settles. */
    private const val SETTLE_MS = 1500L

    fun interface Listener {
        /** @param changed whether the address in use is now a different one */
        fun onChecked(changed: Boolean)
    }

    /*
     * Short timeouts: this only asks whether the local address is there. At
     * home it answers in milliseconds; away, an address on someone else's
     * network (or none) must not hold the switch up for the twenty seconds the
     * Subsonic client would wait.
     */
    private val probe: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Listener>()
    private var running = false
    private val settled = Runnable { check(null) }

    /**
     * Decides which address to use, off the main thread; the listener hears
     * the outcome on the main thread. Calls made while a check is running
     * share its answer.
     */
    @JvmStatic
    fun check(listener: Listener?) {
        synchronized(this) {
            listener?.let(pending::add)
            if (running) return
            running = true
        }
        executor.execute {
            val target = choose()
            main.post {
                val changed = target != null && adopt(target)
                val listeners = synchronized(this) {
                    running = false
                    pending.toList().also { pending.clear() }
                }
                listeners.forEach { it.onChecked(changed) }
            }
        }
    }

    /** Checks again whenever the phone moves to another network. */
    @JvmStatic
    fun watchNetwork(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = settle()

                override fun onLost(network: Network) = settle()
            })
        } catch (e: RuntimeException) {
            // Too many callbacks registered, or no permission: fall back to the
            // checks on resume and on failed tracks.
            Log.w(TAG, "network callback not registered", e)
        }
    }

    private fun settle() {
        if (normalize(Preferences.getLocalAddress()) == null) return
        main.removeCallbacks(settled)
        main.postDelayed(settled, SETTLE_MS)
    }

    /* The address to use, as the preference spells it; null when signed out. */
    private fun choose(): String? {
        val server = Preferences.getServer()?.takeIf { normalize(it) != null } ?: return null
        val local = Preferences.getLocalAddress()?.takeIf { normalize(it) != null } ?: return server
        return if (answers(normalize(local)!!)) local else server
    }

    /*
     * An address answers if a Subsonic server answers there - an unsigned ping
     * is refused, but refused in Subsonic's words. Anything else at that
     * address, a router on another network that happens to use the same
     * numbers, does not count.
     */
    private fun answers(base: String): Boolean = try {
        probe.newCall(Request.Builder().url("$base/rest/ping.view?f=json").build()).execute().use {
            it.isSuccessful && it.body?.string()?.contains("subsonic-response") == true
        }
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun adopt(target: String): Boolean {
        if (normalize(target) == normalize(Preferences.getInUseServerAddress())) return false
        Log.i(TAG, "switching to ${if (target == Preferences.getLocalAddress()) "local" else "main"} address")
        Preferences.setInUseServerAddress(target)
        App.refreshSubsonicClient()
        return true
    }

    /**
     * The same request, sent to the address in use now. Only URLs on one of
     * this server's two addresses are touched; with no local address set,
     * nothing is.
     */
    @JvmStatic
    fun rewrite(uri: Uri): Uri {
        val local = normalize(Preferences.getLocalAddress()) ?: return uri
        val server = normalize(Preferences.getServer()) ?: return uri
        val current = normalize(Preferences.getInUseServerAddress()) ?: return uri
        val url = uri.toString()
        for (base in listOf(local, server).sortedByDescending { it.length }) {
            if (base != current && url.startsWith("$base/")) {
                // Addresses only: the query carries the credentials.
                Log.d(TAG, "rewrite $base -> $current")
                return Uri.parse(current + url.substring(base.length))
            }
        }
        return uri
    }

    /**
     * The main address, for keys that must not change with the address in use
     * - a cover fetched at home is the same cover away.
     */
    @JvmStatic
    fun stableBase(): String = normalize(Preferences.getServer()) ?: ""

    private fun normalize(address: String?): String? = address?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
}
