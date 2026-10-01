package com.cappielloantonio.tempo.wave

import android.os.SystemClock
import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.util.NetworkAddress
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.PremiumServer
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class WaveResult {
    data class Ok(val batch: WaveBatch) : WaveResult()

    data class Failed(val reason: Reason, val detail: String? = null) : WaveResult()

    enum class Reason {
        /* No wave service where it was looked for: not installed, or not proxied. */
        UNREACHABLE,
        /* The service runs, but this user has no entry in user_passwords.conf. */
        NOT_CONFIGURED,
        /* The service could not build a batch (no likes yet, AudioMuse down...). */
        UNAVAILABLE
    }
}

/**
 * Talks to the wave service. Callbacks arrive on the main thread, which is
 * where Retrofit delivers them on Android and where both callers - the home
 * screen and the player service - have to touch the player.
 */
object WaveClient {
    private const val TAG = "WaveClient"

    private const val PASSWORD_HEADER = "X-Wave-Password"

    private const val LYRICS_TIMEOUT_S = 8L

    /* How often opening the home screen may ask the server to get a batch ready. */
    private const val PREPARE_EVERY_MS = 15 * 60 * 1000L

    /*
     * A cold first batch builds the listener's whole taste profile on the
     * server and may take a minute on a big library; the batches after it
     * take seconds. The shared Subsonic client gives up at 30 seconds, so the
     * wave has one of its own. No response cache: every answer is new music.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(150, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.MINUTES)
                .addInterceptor { chain -> chain.proceed(withPassword(chain.request())) }
                .build()
    }

    /*
     * The wave reads the listener's ratings, play counts and recent plays
     * from Navidrome's own API, which only takes a real login - a Subsonic
     * token will not do. So the password the app signed in with goes along,
     * in a header rather than the URL (URLs end up in proxy logs), encoded the
     * way Subsonic encodes p= so that any password fits in a header. The
     * service checks it with Navidrome, uses it for the request and keeps
     * nothing.
     *
     * Only where it cannot be read on the way: over HTTPS, or to an address on
     * this network. Over plain HTTP to anywhere else it stays behind, and the
     * service falls back to its own password file for this user.
     */
    private fun withPassword(request: Request): Request {
        val password = Preferences.getPassword()
        if (password.isNullOrEmpty() || !NetworkAddress.isPrivateOrEncrypted(request.url)) return request

        val hex = password.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return request.newBuilder().header(PASSWORD_HEADER, "enc:$hex").build()
    }

    private val gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").create()

    @Volatile
    private var service: Pair<String, WaveService>? = null

    @Volatile
    private var lastPrepare = 0L

    /** The port the service listens on next to Navidrome; see deploy/wave.service. */
    private const val SERVICE_PORT = 8765

    /* After no candidate answered, how long before they are asked again. */
    private const val RETRY_MS = 60 * 1000L

    /* Only asks whether the service is there: an address that is not must not hold a search up. */
    private val probe: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }

    private val resolver = Executors.newSingleThreadExecutor()

    /* The candidates last looked through, and the one among them that answered. */
    @Volatile
    private var found: Pair<List<String>, String>? = null

    @Volatile
    private var missedAt = 0L

    /**
     * Where the service is, as far as is known now: the address set in the
     * settings, or the one of [candidates] found to answer, or else the
     * server's main address with /wave/ - the nginx location the service's
     * README sets up next to Navidrome's.
     *
     * Never blocks; [resolve] does the looking.
     */
    fun baseUrl(): String {
        val candidates = candidates()
        return found?.takeIf { it.first == candidates }?.second ?: candidates.last()
    }

    /*
     * At home the address in use is usually Navidrome itself, past the proxy
     * the wave sits behind - but the service runs on the same machine, on its
     * own port, and answers there without any proxy set up. So that is tried
     * first, and the main address with /wave/ after it, which reaches the
     * proxy from anywhere.
     */
    private fun candidates(): List<String> {
        val custom = Preferences.getWaveUrl()?.trim()
        if (!custom.isNullOrEmpty()) return listOf(if (custom.endsWith("/")) custom else "$custom/")

        val main = Preferences.getServer()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: App.getSubsonicClientInstance(false).url.removeSuffix("/rest/").trimEnd('/')
        val inUse = Preferences.getInUseServerAddress()?.trim()?.trimEnd('/')?.toHttpUrlOrNull()

        val nextToServer = inUse
                ?.takeIf { it.toString().trimEnd('/') != main }
                ?.let { HttpUrl.Builder().scheme(it.scheme).host(it.host).port(SERVICE_PORT).addPathSegment("wave").build().toString() }
                ?.let { if (it.endsWith("/")) it else "$it/" }

        return listOfNotNull(nextToServer, "$main/wave/").distinct()
    }

    /**
     * Finds which candidate the service answers at. Blocking: for a
     * background thread. Asked once for each set of candidates - they change
     * as the phone leaves home or comes back.
     */
    private fun resolve() {
        val candidates = candidates()
        if (candidates.size < 2 || found?.first == candidates) return
        if (missedAt != 0L && SystemClock.elapsedRealtime() - missedAt < RETRY_MS) return

        val answering = candidates.firstOrNull(::answers)
        if (answering == null) {
            missedAt = SystemClock.elapsedRealtime()
            return
        }
        missedAt = 0L
        found = candidates to answering
    }

    private fun answers(base: String): Boolean = try {
        probe.newCall(Request.Builder().url("${base}ping").build()).execute().use {
            it.isSuccessful && it.body?.string()?.contains("\"ok\"") == true
        }
    } catch (e: Exception) {
        false
    }

    private fun service(): WaveService {
        val url = baseUrl()
        service?.takeIf { it.first == url }?.let { return it.second }

        val created = Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(http)
                .build()
                .create(WaveService::class.java)
        service = url to created
        return created
    }

    private fun auth(): Map<String, String> = App.getSubsonicClientInstance(false).params

    /*
     * The service runs only next to Timoha Premium. On any other server there
     * is nothing to ask, and asking would only probe addresses that cannot answer.
     */
    @JvmStatic
    fun isAvailable(): Boolean = PremiumServer.isInUse()

    fun start(callback: (WaveResult) -> Unit) {
        if (!isAvailable()) {
            callback(WaveResult.Failed(WaveResult.Reason.UNREACHABLE))
            return
        }
        call({ service().start(auth()) }, callback)
    }

    fun next(session: String, events: List<WaveEvent>, callback: (WaveResult) -> Unit) {
        call({ service().next(auth(), WaveNextBody(session, events)) }, callback)
    }

    /**
     * Songs in the library whose embedded lyrics hold [query], or null when
     * the service could not be asked - not there, too old to know the route,
     * or not answering. Blocking: for a background thread.
     *
     * Unlike a batch, a search has someone waiting on it, so it gets seconds,
     * not the minutes a cold batch is allowed.
     */
    fun searchLyrics(query: String, limit: Int): WaveLyrics? = if (!isAvailable()) null else try {
        resolve()
        val call = service().searchLyrics(auth(), query, limit)
        call.timeout().timeout(LYRICS_TIMEOUT_S, TimeUnit.SECONDS)
        call.execute().takeIf { it.isSuccessful }?.body()
    } catch (e: Exception) {
        null
    }

    /**
     * Asks the server to have a first batch ready before the button is
     * pressed - and, before that, finds where the server is, so the press
     * goes to the right address.
     */
    fun prepare() {
        if (!isAvailable()) return
        val now = SystemClock.elapsedRealtime()
        if (lastPrepare != 0L && now - lastPrepare < PREPARE_EVERY_MS) {
            resolver.execute(::resolve)
            return
        }
        lastPrepare = now

        resolver.execute {
            resolve()
            requestPrepare()
        }
    }

    private fun requestPrepare() {
        val call = try {
            service().prepare(auth())
        } catch (e: Exception) {
            return
        }
        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                response.body()?.close()
                response.errorBody()?.close()
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                // A server without the wave: try again on a later visit, not on every one.
            }
        })
    }

    private fun call(make: () -> Call<WaveBatch>, callback: (WaveResult) -> Unit) {
        val call = try {
            make()
        } catch (e: Exception) {
            // A malformed address in the settings fails here, before any request.
            Log.w(TAG, "wave call not built", e)
            callback(WaveResult.Failed(WaveResult.Reason.UNREACHABLE, e.message))
            return
        }

        call.enqueue(object : Callback<WaveBatch> {
            override fun onResponse(call: Call<WaveBatch>, response: Response<WaveBatch>) {
                val body = response.body()
                if (response.isSuccessful && body?.session != null && !body.tracks.isNullOrEmpty()) {
                    callback(WaveResult.Ok(body))
                    return
                }

                val error = response.errorBody()?.use { it.string() }
                callback(failure(response.code(), error))
            }

            override fun onFailure(call: Call<WaveBatch>, t: Throwable) {
                Log.w(TAG, "wave call failed", t)
                callback(WaveResult.Failed(WaveResult.Reason.UNREACHABLE, t.message))
            }
        })
    }

    /*
     * FastAPI puts the reason under "detail": an object for the auth errors,
     * a plain string for a batch that could not be built.
     */
    private fun failure(code: Int, body: String?): WaveResult.Failed {
        val detail = try {
            val json = JsonParser.parseString(body ?: "").asJsonObject
            val inner = json.get("detail")
            when {
                inner == null -> json.get("error")?.asString
                inner.isJsonObject -> inner.asJsonObject.get("error")?.asString
                else -> inner.asString
            }
        } catch (e: Exception) {
            null
        }

        return when {
            code == 403 || detail == "user_not_configured" ->
                WaveResult.Failed(WaveResult.Reason.NOT_CONFIGURED, detail)
            code == 503 || code == 200 -> WaveResult.Failed(WaveResult.Reason.UNAVAILABLE, detail)
            else -> WaveResult.Failed(WaveResult.Reason.UNREACHABLE, detail ?: "HTTP $code")
        }
    }
}
