package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.api.SubsonicException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubsonicClientTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client(salt: String = "abc") =
        SubsonicClient(Credentials(server.url("/").toString(), "tim", Auth.token("secret", salt), salt))

    @Test
    fun `token is md5 of password plus salt`() {
        // The worked example from the Subsonic API documentation.
        assertEquals("26719a1196d2a940705a59634eb18eab", Auth.token("sesame", "c19b2d"))
    }

    @Test
    fun `rest base accepts addresses without a scheme or with a trailing slash`() {
        assertEquals("https://music.example.com/rest/", Auth.restBase("music.example.com"))
        assertEquals("http://host:4533/rest/", Auth.restBase(" http://host:4533/ "))
        assertEquals("https://host/navidrome/rest/", Auth.restBase("https://host/navidrome"))
    }

    @Test
    fun `stream url carries auth, id and transcoding options`() {
        val url = client().streamUrl("song 1", maxBitRate = 192, format = "mp3")
        val parsed = url.toHttpUrl()
        assertEquals(listOf("rest", "stream"), parsed.pathSegments)
        assertEquals("tim", parsed.queryParameter("u"))
        assertEquals(Auth.token("secret", "abc"), parsed.queryParameter("t"))
        assertEquals("abc", parsed.queryParameter("s"))
        assertEquals("1.16.1", parsed.queryParameter("v"))
        assertEquals("Platter", parsed.queryParameter("c"))
        assertEquals("song 1", parsed.queryParameter("id"))
        assertEquals("192", parsed.queryParameter("maxBitRate"))
        assertEquals("mp3", parsed.queryParameter("format"))
        assertTrue("p" !in parsed.queryParameterNames, "the password must never be sent")
    }

    @Test
    fun `album list is parsed and request carries auth params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[
                    {"id":"a1","name":"Kind of Blue","artist":"Miles Davis","coverArt":"al-a1","songCount":5,"year":1959,"starred":"2024-01-01T10:00:00Z"},
                    {"id":"a2","name":"Blue Train","artist":"John Coltrane","displayArtist":"John Coltrane & Co"}]}}}"""
            )
        )

        val albums = client().albumList("newest", size = 2)

        assertEquals(listOf("Kind of Blue", "Blue Train"), albums.map { it.name })
        assertEquals("al-a1", albums[0].coverArtId)
        assertEquals("John Coltrane & Co", albums[1].artistLine())
        val request = server.takeRequest()
        assertEquals("/rest/getAlbumList2", request.requestUrl!!.encodedPath)
        assertEquals("newest", request.requestUrl!!.queryParameter("type"))
        assertEquals("tim", request.requestUrl!!.queryParameter("u"))
        assertEquals("json", request.requestUrl!!.queryParameter("f"))
    }

    @Test
    fun `album with songs is parsed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"ok","album":{"id":"a1","name":"Kind of Blue","song":[
                    {"id":"s1","title":"So What","track":1,"duration":562,"bitRate":900,"suffix":"flac"}]}}}"""
            )
        )

        val album = client().album("a1")

        assertEquals("So What", album.songs!!.single().title)
        assertEquals(900, album.songs!!.single().bitrate)
    }

    @Test
    fun `artists are flattened across index letters`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"ok","artists":{"index":[
                    {"name":"A","artist":[{"id":"1","name":"ABBA"}]},
                    {"name":"B","artist":[{"id":"2","name":"Bjork"},{"id":"3","name":"Blur"}]}]}}}"""
            )
        )

        assertEquals(listOf("ABBA", "Bjork", "Blur"), client().artists().map { it.name })
    }

    @Test
    fun `empty result is an empty list, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","albumList2":{}}}"""))
        assertEquals(emptyList(), client().albumList("newest"))
    }

    @Test
    fun `failed status becomes SubsonicException with the server code`() {
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""))

        val e = assertFailsWith<SubsonicException> { runBlocking { client().ping() } }

        assertTrue(e.isWrongCredentials)
        assertEquals("Wrong username or password", e.message)
    }

    @Test
    fun `a non-subsonic body is reported, not a null dereference`() {
        server.enqueue(MockResponse().setBody("""{"hello":"proxy error page"}"""))
        assertFailsWith<SubsonicException> { runBlocking { client().ping() } }
    }

    @Test
    fun `login pings with a freshly derived token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))

        val client = SubsonicClient.login(server.url("/").toString(), " tim ", "secret")

        val request = server.takeRequest().requestUrl!!
        assertEquals("tim", request.queryParameter("u"))
        assertEquals(Auth.token("secret", request.queryParameter("s")!!), request.queryParameter("t"))
        assertEquals(request.queryParameter("t"), client.credentials.token)
    }
}
