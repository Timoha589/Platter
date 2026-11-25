package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.subsonic.RetrofitClient
import com.cappielloantonio.tempo.util.NetworkUtil
import com.cappielloantonio.tempo.util.Preferences
import com.google.gson.annotations.SerializedName
import com.platter.automix.AutoMixResponse
import com.platter.automix.TrackStructure
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Analysis is optional and its requests never hold up playback. */
internal class AutoMixAnalysis(private val onChanged: () -> Unit) {
    private class Envelope(@SerializedName("subsonic-response") val response: Result?)
    private class Result(
        @SerializedName("status") val status: String?,
        @SerializedName("platterAutoMix") val analysis: AutoMixResponse?,
    )

    private interface Service {
        @GET
        fun analysis(@Url url: String, @QueryMap auth: Map<String, String>, @Query("id") ids: List<String>): Call<Envelope>
    }

    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { request() }
    private var call: Call<Envelope>? = null
    private var account: Pair<String, String>? = null
    private var routes = emptyList<String>()
    private var ids = emptyList<String>()
    private var ready = emptyMap<String, TrackStructure>()
    private var pending = emptySet<String>()
    private var preferredRoute: String? = null

    operator fun get(id: String): TrackStructure? = ready[id]

    fun update(nextIds: List<String>) {
        val server = Preferences.getServer()?.trim()?.trimEnd('/')
        val user = Preferences.getUser()
        val nextAccount = if (!server.isNullOrEmpty() && !user.isNullOrEmpty()) server to user else null
        val nextRoutes = listOfNotNull(Preferences.getInUseServerAddress(), server)
            .map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        val wanted = nextIds.filter { it.isNotEmpty() }.distinct().takeIf { nextAccount != null }.orEmpty()
        if (wanted == ids && nextAccount == account && nextRoutes == routes) return

        cancelRequest()
        if (nextAccount != account) {
            ready = emptyMap()
            pending = emptySet()
            preferredRoute = null
        }
        if (nextRoutes != routes) preferredRoute = null
        account = nextAccount
        routes = nextRoutes
        ids = wanted
        ready = ready.filterKeys { it in ids }
        pending = pending.intersect(ids.toSet())
        request()
    }

    fun release() {
        cancelRequest()
        ids = emptyList()
        ready = emptyMap()
        pending = emptySet()
    }

    private fun cancelRequest() {
        handler.removeCallbacks(retry)
        call?.cancel()
        call = null
    }

    private fun request() {
        val missing = ids.filter { it !in ready || it in pending }
        if (missing.isEmpty()) return
        if (NetworkUtil.isOffline()) {
            handler.postDelayed(retry, RETRY_MS)
            return
        }
        val subsonic = App.getSubsonicClientInstance(false)
        val service = RetrofitClient(subsonic).retrofit.create(Service::class.java)
        val addresses = preferredRoute?.let(::listOf) ?: routes
        requestFrom(service, subsonic.params, addresses, missing)
    }

    private fun requestFrom(
        service: Service,
        auth: Map<String, String>,
        addresses: List<String>,
        missing: List<String>,
    ) {
        val base = addresses.firstOrNull() ?: return
        val request = service.analysis("$base/rest/platterAutoMix", auth, missing)
        request.timeout().timeout(5, TimeUnit.SECONDS)
        call = request
        request.enqueue(object : Callback<Envelope> {
            override fun onResponse(call: Call<Envelope>, response: Response<Envelope>) {
                if (this@AutoMixAnalysis.call !== call) return
                this@AutoMixAnalysis.call = null
                val result = response.body()?.response
                val analysis = result?.analysis
                if (analysis == null) {
                    response.errorBody()?.close()
                    if (response.code() in listOf(404, 405, 501) || result?.status == "ok") {
                        val fallback = addresses.drop(1)
                        fallback.firstOrNull()?.let { preferredRoute = it }
                        requestFrom(service, auth, fallback, missing)
                    } else {
                        handler.postDelayed(retry, RETRY_MS)
                    }
                    return
                }
                if (result.status != "ok") {
                    handler.postDelayed(retry, RETRY_MS)
                    return
                }
                val previous = ready
                ready = ready + analysis.tracks.orEmpty().filterKeys { it in missing }
                pending = analysis.pending.orEmpty().intersect(missing.toSet())
                if (pending.isNotEmpty()) handler.postDelayed(retry, PENDING_MS)
                if (previous != ready) onChanged()
            }

            override fun onFailure(call: Call<Envelope>, error: Throwable) {
                if (this@AutoMixAnalysis.call !== call) return
                this@AutoMixAnalysis.call = null
                handler.postDelayed(retry, RETRY_MS)
            }
        })
    }

    private companion object {
        const val PENDING_MS = 8_000L
        const val RETRY_MS = 30_000L
    }
}
