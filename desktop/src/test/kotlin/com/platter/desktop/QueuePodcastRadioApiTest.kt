package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SavedQueue
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The saved queue, podcasts and internet radio, read and written through the real client. */
class QueuePodcastRadioApiTest {
    private fun client(fake: FakeSubsonic) = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))

    private fun ids(n: Int, album: Int = 1) = (1..n).map { "s$album-$it" }

    // --- the saved queue -------------------------------------------------------------

    @Test
    fun `extensions are asked once and a server that does not know the question has none`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        assertEquals(setOf("indexBasedQueue", "formPost"), runBlocking { client.extensions() })
        runBlocking { client.extensions() }
        assertEquals(1, fake.requestsTo("getOpenSubsonicExtensions").size)
    }

    @Test
    fun `with both extensions the queue goes up as a form post by index`() = FakeSubsonic().use { fake ->
        runBlocking { client(fake).savePlayQueue(ids(3), index = 2, positionMs = 61_500) }
        val (endpoint, body) = fake.formBodies.single()
        assertEquals("savePlayQueueByIndex", endpoint)
        assertTrue("currentIndex=2" in body && "position=61500" in body, body)
        assertEquals(3, "id=".toRegex().findAll(body).count())
        // And the server heard it the same way.
        assertEquals(2, fake.savedQueue?.currentIndex)
        assertEquals(61_500L, fake.savedQueue?.position)
    }

    @Test
    fun `with only the index extension it is a get by index`() = FakeSubsonic().use { fake ->
        fake.extensions = listOf("indexBasedQueue")
        runBlocking { client(fake).savePlayQueue(ids(3), index = 1, positionMs = 5) }
        val request = fake.requestsTo("savePlayQueueByIndex").single()
        assertEquals("1", request.queryParameter("currentIndex"))
        assertEquals(listOf("s1-1", "s1-2", "s1-3"), request.queryParameterValues("id"))
        assertTrue(fake.formBodies.isEmpty())
    }

    @Test
    fun `without extensions the playing song is named by its id`() = FakeSubsonic().use { fake ->
        fake.extensions = emptyList()
        runBlocking { client(fake).savePlayQueue(ids(3), index = 1, positionMs = 5) }
        val request = fake.requestsTo("savePlayQueue").single()
        assertEquals("s1-2", request.queryParameter("current"))
        assertNull(request.queryParameter("currentIndex"))
    }

    @Test
    fun `a long queue without form posts is cut to a window around the playing song`() = FakeSubsonic().use { fake ->
        fake.extensions = listOf("indexBasedQueue")
        val long = (1..600).map { "s1-$it" }
        runBlocking { client(fake).savePlayQueue(long, index = 300, positionMs = 0) }
        val request = fake.requestsTo("savePlayQueueByIndex").single()
        val sent = request.queryParameterValues("id")
        assertEquals(250, sent.size)
        // The same song is still the one playing, whatever the window cut away before it.
        assertEquals("s1-301", sent[request.queryParameter("currentIndex")!!.toInt()])
        assertTrue(request.toString().length < 8_000, "short enough for a proxy: ${request.toString().length}")

        // With form posts the whole queue goes.
        fake.extensions = listOf("indexBasedQueue", "formPost")
        val other = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        runBlocking { other.savePlayQueue(long, index = 300, positionMs = 0) }
        assertEquals(600, "id=".toRegex().findAll(fake.formBodies.single().second).count())
    }

    @Test
    fun `an empty queue is not saved`() = FakeSubsonic().use { fake ->
        runBlocking { client(fake).savePlayQueue(emptyList(), 0, 0) }
        assertTrue(fake.requests.none { it.pathSegments.last().startsWith("savePlayQueue") })
    }

    @Test
    fun `a saved queue is read back with its songs, index and position, by whichever endpoint`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        assertNull(runBlocking { client.playQueue() }?.takeIf { it.resolvedIndex() >= 0 }, "nothing saved yet")

        runBlocking { client.savePlayQueue(ids(4), index = 3, positionMs = 90_000) }
        val saved = runBlocking { client.playQueue() }!!
        assertEquals(4, saved.entries!!.size)
        assertEquals(3, saved.resolvedIndex())
        assertEquals(90_000L, saved.position)
        assertEquals("Track 4 of Blue Train", saved.entries!![3].title)

        // A server without the extension answers under the other key.
        fake.extensions = emptyList()
        val classic = runBlocking { SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")).playQueue() }!!
        assertEquals(1, fake.requestsTo("getPlayQueue").size)
        assertNotNull(classic.current)
    }

    @Test
    fun `where to resume prefers the index, then the id, and says -1 when it cannot tell`() {
        fun queue(vararg ids: String) = SavedQueue().apply { entries = ids.map { id -> Song().apply { this.id = id } } }
        assertEquals(-1, SavedQueue().resolvedIndex())
        assertEquals(1, queue("a", "b", "a").apply { current = "b" }.resolvedIndex())
        // The same song twice: the id picks the first, the index is exact.
        assertEquals(0, queue("a", "b", "a").apply { current = "a" }.resolvedIndex())
        assertEquals(2, queue("a", "b", "a").apply { current = "a"; currentIndex = 2 }.resolvedIndex())
        // An index past the end is not believed; the id is the fallback.
        assertEquals(1, queue("a", "b").apply { current = "b"; currentIndex = 9 }.resolvedIndex())
        assertEquals(-1, queue("a", "b").apply { current = "zzz" }.resolvedIndex())
    }

    // --- podcasts -----------------------------------------------------------------------

    @Test
    fun `channels, one channel with its episodes, and the newest`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        val channels = runBlocking { client.podcastChannels() }
        assertEquals(listOf("Tech Talk", "Broken feed"), channels.map { it.title })
        assertNull(channels.first().episodes, "the list does not carry episodes")
        assertEquals("false", fake.requestsTo("getPodcasts").single().queryParameter("includeEpisodes"))

        val channel = runBlocking { client.podcastChannel("c1") }
        assertEquals(3, channel.episodes!!.size)
        assertEquals(listOf(true, true, false), channel.episodes!!.map { it.isDownloaded })
        assertEquals("s0-1", channel.episodes!!.first().streamId)

        assertEquals(listOf("e1", "e2"), runBlocking { client.newestEpisodes(5) }.map { it.id })
        assertEquals("5", fake.requestsTo("getNewestPodcasts").single().queryParameter("count"))
    }

    @Test
    fun `an episode is playable only once the server has downloaded it`() = FakeSubsonic().use { fake ->
        val episodes = runBlocking { client(fake).podcastChannel("c1") }.episodes!!
        assertTrue(episodes[0].isDownloaded)
        assertTrue(!episodes[2].isDownloaded, "status ${episodes[2].status}, no stream id")
    }

    @Test
    fun `podcasts are added, refreshed, downloaded and deleted by their ids`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        runBlocking {
            client.addPodcast("http://feeds.test/new")
            client.refreshPodcasts()
            client.downloadEpisode("e3")
            client.deleteEpisode("e2")
            client.deletePodcast("c2")
        }
        assertEquals("http://feeds.test/new", fake.requestsTo("createPodcastChannel").single().queryParameter("url"))
        assertEquals(1, fake.requestsTo("refreshPodcasts").size)
        assertEquals("e3", fake.requestsTo("downloadPodcastEpisode").single().queryParameter("id"))
        assertEquals("e2", fake.requestsTo("deletePodcastEpisode").single().queryParameter("id"))
        assertEquals("c2", fake.requestsTo("deletePodcastChannel").single().queryParameter("id"))
        assertEquals(listOf("Tech Talk", "http://feeds.test/new"), runBlocking { client.podcastChannels() }.map { it.title })
    }

    // --- internet radio ------------------------------------------------------------------

    @Test
    fun `stations are listed, added, changed and deleted`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        val first = runBlocking { client.radioStations() }.single()
        assertEquals("Jazz FM", first.name)
        assertTrue(first.streamUrl!!.endsWith("/radio/jazz.wav"))
        assertEquals("http://jazz.test", first.homePageUrl)

        runBlocking { client.addStation("Rock FM", "http://rock.test/stream", "  ") }
        val added = fake.requestsTo("createInternetRadioStation").single()
        assertEquals("http://rock.test/stream", added.queryParameter("streamUrl"))
        assertEquals("Rock FM", added.queryParameter("name"))
        assertNull(added.queryParameter("homepageUrl"), "a blank website is not sent")

        runBlocking { client.updateStation("r2", "Rock FM 2", "http://rock.test/2", "http://rock.test") }
        runBlocking { client.deleteStation("r1") }
        val stations = runBlocking { client.radioStations() }
        assertEquals(listOf("Rock FM 2"), stations.map { it.name })
        assertEquals("http://rock.test/2", stations.single().streamUrl)
    }
}
