package com.platter.desktop.wave

import com.google.gson.JsonParser
import com.platter.desktop.api.Auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.util.concurrent.TimeUnit

/** Timoha Premium: the Navidrome server that runs the My Wave service and the lyrics index beside it. */
object PremiumServer {
    const val NAME = "Timoha Premium"
    const val HOST = "music.timoha.top"
    const val ADDRESS = "https://$HOST"

    fun isPremium(address: String?): Boolean {
        val trimmed = address?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val url = (if (trimmed.contains("://")) trimmed else "https://$trimmed").toHttpUrlOrNull()
        return url?.host.equals(HOST, ignoreCase = true)
    }
}

object NetworkAddress {
    /** Whether a password sent to [url] cannot be read on the way: the connection is encrypted, or it never leaves this network. */
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

interface WaveService {
    @POST("start")
    suspend fun start(@QueryMap auth: Map<String, String>): Response<WaveBatch>

    @POST("next")
    suspend fun next(@QueryMap auth: Map<String, String>, @Body body: WaveNextBody): Response<WaveBatch>

    @POST("prepare")
    suspend fun prepare(@QueryMap auth: Map<String, String>): Response<ResponseBody>

    @GET("lyrics/search")
    suspend fun searchLyrics(@QueryMap auth: Map<String, String>, @Query("q") query: String, @Query("limit") limit: Int): Response<WaveLyrics>
}

/**
 * Talks to the wave service. Every call is signed the way a Subsonic call is -
 * u, t and s in the query - and the service checks that signature with
 * Navidrome.
 *
 * The wave reads the listener's ratings, play counts and recent plays from
 * Navidrome's own API, which only takes a real login, so the password goes
 * along too: in a header rather than the URL (URLs end up in proxy logs),
 * encoded the way Subsonic encodes `p=` so any password fits in a header. The
 * service checks it with Navidrome, uses it for the request and keeps nothing.
 * Only where it cannot be read on the way - over HTTPS, or to an address on
 * this network; over plain HTTP anywhere else it stays behind and the service
 * falls back to its own password file.
 */
class WaveClient(
    val baseUrl: String,
    private val auth: Map<String, String>,
    private val password: () -> String?,
) {
    /*
     * A cold first batch builds the listener's whole taste profile on the
     * server and may take a minute on a big library, so the wave gets a client
     * of its own with long timeouts. No response cache: every answer is new music.
     */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .addInterceptor { chain -> chain.proceed(withPassword(chain.request())) }
        .build()

    private val service: WaveService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(http)
        .build()
        .create(WaveService::class.java)

    private fun withPassword(request: Request): Request {
        val pw = password()
        if (pw.isNullOrEmpty() || !NetworkAddress.isPrivateOrEncrypted(request.url)) return request
        val hex = pw.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return request.newBuilder().header(PASSWORD_HEADER, "enc:$hex").build()
    }

    suspend fun start(): WaveResult = batch { service.start(auth) }

    suspend fun next(session: String, events: List<WaveEvent>): WaveResult = batch { service.next(auth, WaveNextBody(session, events)) }

    /**
     * Songs in the library whose embedded lyrics hold [query], or null when
     * the service could not be asked - not there, too old to know the route, or
     * not answering. A search has someone waiting on it, so it gets seconds,
     * not the minutes a cold batch is allowed.
     */
    suspend fun searchLyrics(query: String, limit: Int): WaveLyrics? = try {
        withTimeoutOrNull(LYRICS_TIMEOUT_MS) { service.searchLyrics(auth, query, limit).takeIf { it.isSuccessful }?.body() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Asks the server to have a first batch ready before the button is pressed. Best effort. */
    suspend fun prepare() {
        try {
            service.prepare(auth).body()?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A server without the wave: try again on a later visit.
        }
    }

    private suspend fun batch(call: suspend () -> Response<WaveBatch>): WaveResult {
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return WaveResult.Failed(WaveResult.Reason.UNREACHABLE, e.message)
        }
        val body = response.body()
        if (response.isSuccessful && body?.session != null && !body.tracks.isNullOrEmpty()) return WaveResult.Ok(body)
        return failure(response.code(), response.errorBody()?.use { it.string() })
    }

    /** FastAPI puts the reason under "detail": an object for the auth errors, a plain string for a batch that could not be built. */
    internal fun failure(code: Int, body: String?): WaveResult.Failed {
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
            code == 403 || detail == "user_not_configured" -> WaveResult.Failed(WaveResult.Reason.NOT_CONFIGURED, detail)
            code == 503 || code == 200 -> WaveResult.Failed(WaveResult.Reason.UNAVAILABLE, detail)
            else -> WaveResult.Failed(WaveResult.Reason.UNREACHABLE, detail ?: "HTTP $code")
        }
    }

    companion object {
        const val PASSWORD_HEADER = "X-Wave-Password"
        private const val LYRICS_TIMEOUT_MS = 8_000L

        /** The setting if there is one, else the server's own address with /wave/ - the nginx location the service's README sets up. */
        fun baseUrlFor(serverUrl: String, custom: String?): String {
            val c = custom?.trim()
            if (!c.isNullOrEmpty()) return if (c.endsWith("/")) c else "$c/"
            return Auth.restBase(serverUrl).removeSuffix("rest/") + "wave/"
        }

        /** Offered on Timoha Premium, or anywhere a service address has been set by hand. */
        fun isAvailable(serverUrl: String?, custom: String?): Boolean =
            !custom.isNullOrBlank() || PremiumServer.isPremium(serverUrl)
    }
}
