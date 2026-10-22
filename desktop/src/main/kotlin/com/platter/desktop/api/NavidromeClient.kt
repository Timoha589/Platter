package com.platter.desktop.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.platter.desktop.wave.NetworkAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface NavidromeService {
    @POST("auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("api/song")
    suspend fun songs(
        @Header("X-ND-Authorization") auth: String,
        @Query("_start") start: Int,
        @Query("_end") end: Int,
        @Query("_sort") sort: String,
        @Query("_order") order: String,
    ): Response<ResponseBody>
}

/**
 * Navidrome's own API, beside the Subsonic one, for what Subsonic cannot be asked: which songs were played last.
 * Subsonic only knows the albums. The history is the server's, so plays from the phone are in it too.
 *
 * It takes a real login, so the password goes along - the same rule as the wave's: only over HTTPS or to an address
 * on this network. The wave service asks the same `api/song?_sort=playDate` of the same server.
 */
class NavidromeClient(
    serverUrl: String,
    private val username: String,
    private val password: () -> String?,
    http: OkHttpClient = SubsonicClient.defaultHttp,
) {
    /** The server's root: where `rest/` hangs from, and `auth/` and `api/` beside it. */
    val baseUrl: HttpUrl = Auth.restBase(serverUrl).removeSuffix("rest/").toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Bad server address: $serverUrl")

    private val service: NavidromeService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(http)
        .build()
        .create(NavidromeService::class.java)

    @Volatile
    private var token: String? = null

    /**
     * The songs played last, newest first, [size] of them from [offset]; fewer when the history runs out. Null when the
     * server cannot be asked: it is not Navidrome, refuses the login, or the password is not at hand or would cross the
     * internet in the clear. Throws when the server does not answer at all.
     */
    suspend fun recentlyPlayed(offset: Int, size: Int): List<Song>? {
        var bearer = token ?: login() ?: return null
        var response = service.songs("Bearer $bearer", offset, offset + size, "playDate", "DESC")
        if (response.code() == 401) {
            // A session runs out after a day or two; one fresh login, then it is not to be had.
            token = null
            bearer = login() ?: return null
            response = service.songs("Bearer $bearer", offset, offset + size, "playDate", "DESC")
        }
        val body = response.body()?.use { it.string() }
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            return null
        }
        return parseHistory(body)
    }

    private suspend fun login(): String? {
        val pass = password()
        if (pass.isNullOrEmpty() || !NetworkAddress.isPrivateOrEncrypted(baseUrl)) return null
        val response = service.login(mapOf("username" to username, "password" to pass))
        val body = response.body()?.use { it.string() }
        response.errorBody()?.close()
        if (!response.isSuccessful || body == null) return null
        return runCatching { JsonParser.parseString(body).asJsonObject.get("token")?.asString }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { token = it }
    }

    companion object {
        /**
         * The rows of `api/song`, as songs, up to the first one never played: sorted by the date of the last play, the
         * songs never heard come after all the rest. Some versions wrap the list as `{"data": [...]}`.
         */
        internal fun parseHistory(body: String): List<Song> {
            val json = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return emptyList()
            val rows = when {
                json.isJsonArray -> json.asJsonArray
                json.isJsonObject && json.asJsonObject.get("data")?.isJsonArray == true -> json.asJsonObject.getAsJsonArray("data")
                else -> return emptyList()
            }
            return rows.asSequence()
                .filter { it.isJsonObject }
                .map { it.asJsonObject }
                .takeWhile { played(it) }
                .mapNotNull(::song)
                .toList()
        }

        /** Go writes "no time" as the year 1; a song with no plays has no date worth the name. */
        private fun played(row: JsonObject): Boolean {
            val date = row.text("playDate") ?: return false
            return !date.startsWith("0001-") && (row.number("playCount") ?: 1.0) > 0
        }

        private fun song(row: JsonObject): Song? {
            val id = row.text("id") ?: return null
            return Song().also {
                it.id = id
                it.title = row.text("title")
                it.album = row.text("album")
                it.albumId = row.text("albumId")
                it.artist = row.text("artist")
                it.artistId = row.text("artistId")
                it.track = row.number("trackNumber")?.toInt()?.takeIf { n -> n > 0 }
                it.discNumber = row.number("discNumber")?.toInt()?.takeIf { n -> n > 0 }
                it.year = row.number("year")?.toInt()?.takeIf { n -> n > 0 }
                it.genre = row.text("genre")
                // Navidrome's Subsonic API names a song's picture this way; it falls back to the album's by itself.
                it.coverArtId = "mf-$id"
                it.duration = row.number("duration")?.toInt()
                it.bitrate = row.number("bitRate")?.toInt()
                it.suffix = row.text("suffix")
                it.size = row.number("size")?.toLong()
                it.starred = row.text("starredAt")?.takeIf { row.get("starred")?.let { s -> s.isJsonPrimitive && s.asBoolean } == true }
                it.userRating = row.number("rating")?.toInt()?.takeIf { n -> n > 0 }
                it.playCount = row.number("playCount")?.toLong()
                it.played = row.text("playDate")
            }
        }

        private fun JsonObject.text(name: String): String? =
            get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf { it.isNotBlank() }

        private fun JsonObject.number(name: String): Double? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    }
}
