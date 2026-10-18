package com.platter.desktop

import com.platter.desktop.api.Artist
import com.platter.desktop.api.ArtistCredit
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Joint credits taken apart, and the songs an artist made with others gathered back onto their page. */
class CollaborationsTest {
    private val apps = ArrayList<AppController>()

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun app(fake: FakeSubsonic): AppController {
        val store = SettingsStore(Files.createTempDirectory("platter-collab").resolve("settings.json"))
        store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy")).also { apps += it }
    }

    private fun artist(id: String?, name: String) = Artist().apply { this.id = id; this.name = name }
    private fun song(id: String, credit: String, year: Int) = Song().apply { this.id = id; artist = credit; this.year = year }

    // --- the rules -------------------------------------------------------------------------

    @Test
    fun `a credit splits where the server's tags join names`() {
        assertEquals(listOf("Illenium", "Thirty Seconds to Mars"), ArtistCredit.split("Illenium/Thirty Seconds to Mars"))
        assertEquals(listOf("Illenium", "Bastille", "Dabin"), ArtistCredit.split("Illenium, Bastille, Dabin"))
        assertEquals(listOf("Halsey", "Illenium"), ArtistCredit.split("Halsey feat. Illenium"))
        assertEquals(listOf("A", "B"), ArtistCredit.split("A; B; a"))
        assertEquals(listOf("Кино"), ArtistCredit.split("Кино"))
        assertEquals(emptyList(), ArtistCredit.split("  "))
    }

    @Test
    fun `a name with a separator in it stays one artist when nothing else knows its parts`() {
        val acdc = artist("x", "AC/DC")
        assertNull(ArtistCredit.resolve("AC/DC", listOf(listOf(acdc), listOf(acdc))))
    }

    @Test
    fun `a name with a comma in it is one name when other credits hold it whole`() {
        // As the server has it: the band on its own, and in credits joined with slashes.
        val band = artist("ewf", "Earth, Wind & Fire")
        val hits = listOf(band, artist("j1", "Earth, Wind & Fire/Kenny G"), artist("j2", "Barbra Streisand/Earth, Wind & Fire"))
        assertNull(ArtistCredit.resolve("Earth, Wind & Fire", listOf(hits, hits)))

        val resolved = ArtistCredit.resolve("Earth, Wind & Fire/Kenny G", listOf(hits, hits, listOf(artist("kg", "Kenny G"))))!!
        assertEquals(listOf("Earth, Wind & Fire", "Kenny G"), resolved.map { it.name })
        assertEquals(listOf("ewf", "kg"), resolved.map { it.id })

        assertEquals(true, ArtistCredit.isCollaboration("Earth, Wind & Fire/Kenny G", "Earth, Wind & Fire"))
        assertEquals(false, ArtistCredit.isCollaboration("Earth, Wind & Fire", "Earth, Wind & Fire"))
    }

    @Test
    fun `an album's comma credit splits when nothing holds it whole`() {
        val hits = listOf(artist("j2", "Illenium, Thirty Seconds to Mars"), artist("j3", "Illenium/Teddy Swims"), artist("30", "Thirty Seconds to Mars"))
        val resolved = ArtistCredit.resolve("Illenium, Thirty Seconds to Mars", listOf(hits, hits))!!
        assertEquals(listOf(null, "30"), resolved.map { it.id })
    }

    @Test
    fun `a part is known by its own page, or by turning up in another credit`() {
        // The same pair written the album's way does not count as "another credit".
        val sameCredit = artist("j2", "Illenium, Thirty Seconds to Mars")
        val resolved = ArtistCredit.resolve(
            "Illenium/Thirty Seconds to Mars",
            listOf(listOf(sameCredit, artist("j3", "Illenium/Teddy Swims")), listOf(sameCredit, artist("30", "Thirty Seconds to Mars"))),
        )!!
        assertEquals(listOf("Illenium", "Thirty Seconds to Mars"), resolved.map { it.name })
        assertEquals(listOf(null, "30"), resolved.map { it.id })

        // Only the pair itself: neither is known, so it opens as before.
        assertNull(ArtistCredit.resolve("Illenium/Thirty Seconds to Mars", listOf(listOf(sameCredit), listOf(sameCredit))))
    }

    @Test
    fun `collaborations are the joint credits naming the artist, newest first`() {
        val songs = listOf(
            song("1", "Thirty Seconds to Mars", 2005),
            song("2", "Illenium/Thirty Seconds to Mars", 2021),
            song("3", "Thirty Seconds to Mars/Halsey", 2023),
            song("2", "Illenium/Thirty Seconds to Mars", 2021),
            song("4", "Thirty Seconds to Mars Tribute/Someone", 2010),
        )
        assertEquals(listOf("3", "2"), ArtistCredit.collaborations(songs, "thirty seconds to mars").map { it.id })
    }

    // --- through the app -------------------------------------------------------------------

    @Test
    fun `a joint credit resolves to its artists, one of them with no page of its own`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val artists = runBlocking { app.resolveCredit("Illenium/Thirty Seconds to Mars") }!!
        assertEquals(listOf("Illenium", "Thirty Seconds to Mars"), artists.map { it.name })
        assertEquals(listOf(null, "ar30"), artists.map { it.id })

        // Asked once per credit.
        val searches = fake.requestsTo("search3").size
        runBlocking { app.resolveCredit("Illenium/Thirty Seconds to Mars") }
        assertEquals(searches, fake.requestsTo("search3").size)
    }

    @Test
    fun `the name of a joint credit offers a choice, and a band named with a slash opens its page`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.openArtist("Illenium/Thirty Seconds to Mars", "arj1")
        waitUntil("the choice") { app.dialog is AppDialog.PickArtist }
        val picked = (app.dialog as AppDialog.PickArtist).artists
        app.openPickedArtist(picked.first())
        assertEquals(Screen.Collaborator("Illenium"), app.screen)
        assertNull(app.dialog)

        app.openArtist("AC/DC", "aracdc")
        waitUntil("the band's page") { app.screen == Screen.Artist("aracdc") }
        assertNull(app.dialog)
    }

    @Test
    fun `an artist's collaborations are found by searching for the name`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        assertEquals(listOf("sj1"), runBlocking { app.collaborations("Thirty Seconds to Mars") }.map { it.id })
        assertEquals(listOf("sj2", "sj1"), runBlocking { app.collaborations("Illenium") }.map { it.id })
        assertEquals(emptyList(), runBlocking { app.collaborations("AC/DC") }.map { it.id })
    }
}
