package com.platter.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.data.Settings
import com.platter.desktop.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The remote other programs drive the player with, spoken to over real HTTP the way Jarvis speaks to it. */
class RemoteControlTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-remote-test")
    private val http = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun app(fake: FakeSubsonic?, configure: Settings.() -> Unit = {}): AppController {
        val store = SettingsStore(dir.resolve("settings.json"))
        store.update {
            if (fake != null) withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy"))
            .also { apps += it }
            .also { it.startRemoteControl() }
    }

    private class Answer(val status: Int, val json: JsonObject)

    private fun remoteFile(): JsonObject = JsonParser.parseString(Files.readString(dir.resolve("remote.json"))).asJsonObject

    private fun call(command: String, body: String = "{}", token: String? = remoteFile()["token"].asString): Answer {
        val port = remoteFile()["port"].asInt
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/v1/$command")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { r -> return Answer(r.code, JsonParser.parseString(r.body!!.string()).asJsonObject) }
    }

    /** What [probe] finds within a few seconds, or null: some commands finish after they have answered. */
    private fun <T : Any> eventually(probe: () -> T?): T? {
        repeat(50) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun `it says where it listens, refuses a caller without the token and tidies up after itself`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val file = remoteFile()
        assertTrue(file["port"].asInt > 0)
        assertEquals(48, file["token"].asString.length)

        assertEquals(401, call("status", token = null).status)
        assertEquals(401, call("status", token = "not-it").status)
        assertEquals(200, call("status").status)
        assertEquals(404, call("no-such-thing").status)
        assertEquals(400, call("search", body = "[1, 2]").status)

        app.shutdown()
        apps.remove(app)
        assertFalse(Files.exists(dir.resolve("remote.json")), "the file of a closed Platter is gone")
    }

    @Test
    fun `switched off in the settings, nothing listens`() = FakeSubsonic().use { fake ->
        app(fake) { remoteControl = false }
        assertFalse(Files.exists(dir.resolve("remote.json")))
    }

    @Test
    fun `signed out, it says so rather than doing nothing`() {
        app(fake = null)
        val status = call("status")
        assertEquals(200, status.status)
        assertFalse(status.json["signedIn"].asBoolean)
        assertEquals(409, call("search", """{"query":"abbey"}""").status)
    }

    @Test
    fun `search hands out ids that play_item plays, now, next and at the end`() = FakeSubsonic().use { fake ->
        app(fake)
        val found = call("search", """{"query":"abbey"}""").json
        assertEquals("album", found["top"].asJsonObject["kind"].asString)
        val album = found["albums"].asJsonArray.first().asJsonObject
        assertEquals("Abbey Road", album["name"].asString)

        val played = call("play_item", """{"kind":"album","id":"${album["id"].asString}"}""")
        assertEquals(200, played.status, played.json.toString())
        assertEquals(5, played.json["songs"].asInt)
        assertEquals("Track 1 of Abbey Road", played.json["current"].asJsonObject["title"].asString)
        assertEquals(5, played.json["queueSize"].asInt)

        val next = call("play_item", """{"kind":"song","id":"s4-2","mode":"next"}""").json
        assertEquals("Track 2 of Nevermind", next["first"].asJsonObject["title"].asString)
        val last = call("play_item", """{"kind":"song","id":"s5-1","mode":"last"}""").json
        assertEquals(7, last["queueSize"].asInt)

        val queue = call("queue").json["songs"].asJsonArray.map { it.asJsonObject["id"].asString }
        assertEquals(listOf("s2-1", "s4-2", "s2-2", "s2-3", "s2-4", "s2-5", "s5-1"), queue)

        val jumped = call("jump", """{"index":6}""").json
        assertEquals("s5-1", jumped["current"].asJsonObject["id"].asString)
        assertEquals(400, call("jump", """{"index":7}""").status)
    }

    @Test
    fun `an artist plays its best-known songs, and a song with radio brings similar ones`() = FakeSubsonic().use { fake ->
        app(fake)
        val artist = call("play_item", """{"kind":"artist","id":"ar0"}""").json
        assertEquals("Miles Davis", artist["name"].asString)
        assertEquals(3, artist["songs"].asInt)

        call("play_item", """{"kind":"song","id":"s1-1","radio":true}""")
        // The similar songs come after the seed, a moment later.
        val size = eventually { call("status").json["queueSize"].asInt.takeIf { it > 1 } }
        assertEquals(5, size, "the seed and the four the fake finds like it")
    }

    @Test
    fun `volume, shuffle, repeat and the like of the song playing`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        assertEquals(30, call("volume", """{"level":30}""").json["volume"].asInt)
        assertEquals(20, call("volume", """{"delta":-10}""").json["volume"].asInt)
        assertEquals(0, call("volume", """{"level":-5}""").json["volume"].asInt)
        assertTrue(call("shuffle", """{"on":true}""").json["shuffle"].asBoolean)
        assertTrue(call("shuffle", """{"on":true}""").json["shuffle"].asBoolean, "asked for on, it stays on")
        assertEquals("one", call("repeat", """{"mode":"one"}""").json["repeat"].asString)
        assertEquals("off", call("repeat", """{"mode":"off"}""").json["repeat"].asString)

        assertEquals(409, call("like").status, "nothing plays yet")
        call("play_item", """{"kind":"song","id":"s3-1"}""")
        val liked = call("like").json
        assertTrue(liked["liked"].asBoolean)
        assertTrue(app.isLiked("s3-1", null))
        assertTrue(call("status").json["current"].asJsonObject["liked"].asBoolean)
        call("like", """{"on":false}""")
        assertFalse(app.isLiked("s3-1", null))
    }

    @Test
    fun `a playlist is found by name and gets the song playing`() = FakeSubsonic().use { fake ->
        fake.addPublicPlaylist("p-road", "Road trip", owner = "tim")
        app(fake)
        val lists = call("search", """{"query":"road trip"}""").json["playlists"].asJsonArray
        assertEquals("p-road", lists.single().asJsonObject["id"].asString)

        call("play_item", """{"kind":"song","id":"s2-3"}""")
        val added = call("add_to_playlist", """{"playlistId":"p-road"}""")
        assertEquals(200, added.status, added.json.toString())
        val songs = eventually { fake.playlistSongs("p-road")?.takeIf { "s2-3" in it } }
        assertNotNull(songs, "the song reached the server's playlist")
        Unit
    }
}
