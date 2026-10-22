package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.NavidromeClient
import com.platter.desktop.data.Secrets
import com.platter.desktop.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Last played" as songs: read from Navidrome's own API where it can be, and left to the albums where it cannot. */
class NavidromeHistoryTest {
    private val history = listOf("s2-3", "s4-1", "s1-2", "s6-5", "s2-1")

    @Test
    fun `the history comes newest first and stops at the songs never played`() = FakeSubsonic().use { fake ->
        fake.navidromeHistory = history
        val nd = NavidromeClient(fake.url, "tim", password = { "pw" })
        val songs = runBlocking { nd.recentlyPlayed(0, 50) }
        assertNotNull(songs)
        assertEquals(history, songs.map { it.id })
        val first = songs.first()
        assertEquals("Track 3 of Abbey Road", first.title)
        assertEquals("a2", first.albumId)
        assertEquals("mf-s2-3", first.coverArtId, "the picture is asked of Subsonic by the song's own id")
        assertEquals(201, first.duration)
        assertEquals(3, first.track)

        val query = fake.navidromeQueries.first()
        assertEquals("playDate", query.queryParameter("_sort"))
        assertEquals("DESC", query.queryParameter("_order"))
        assertEquals(listOf("pw"), fake.navidromeLogins.toList(), "one login, kept for the next call")
    }

    @Test
    fun `a page is the stretch asked for, and a session that ran out is renewed once`() = FakeSubsonic().use { fake ->
        fake.navidromeHistory = history
        val nd = NavidromeClient(fake.url, "tim", password = { "pw" })
        assertEquals(listOf("s1-2", "s6-5"), runBlocking { nd.recentlyPlayed(2, 2) }?.map { it.id })
        fake.expireNavidromeSession()
        assertEquals(listOf("s4-1"), runBlocking { nd.recentlyPlayed(1, 1) }?.map { it.id })
        assertEquals(2, fake.navidromeLogins.size)
    }

    @Test
    fun `where the server cannot be asked the answer is null, so the albums stand in`() = FakeSubsonic().use { fake ->
        // A Subsonic server that is not Navidrome.
        assertNull(runBlocking { NavidromeClient(fake.url, "tim", password = { "pw" }).recentlyPlayed(0, 20) })

        fake.navidromeHistory = history
        // No password kept, or the wrong one.
        assertNull(runBlocking { NavidromeClient(fake.url, "tim", password = { null }).recentlyPlayed(0, 20) })
        assertNull(runBlocking { NavidromeClient(fake.url, "tim", password = { "nope" }).recentlyPlayed(0, 20) })

        // A public address over plain HTTP: the password would cross the internet readable, so it is never sent.
        val logins = fake.navidromeLogins.size
        assertNull(runBlocking { NavidromeClient("http://music.example.com", "tim", password = { "pw" }).recentlyPlayed(0, 20) })
        assertEquals(logins, fake.navidromeLogins.size)
    }

    @Test
    fun `older servers wrap the rows, and go writes no date as the year one`() {
        val rows = """{"data":[{"id":"x1","title":"A","playDate":"2026-10-01T10:00:00Z","playCount":2,"duration":61.9},{"id":"x2","title":"B","playDate":"0001-01-01T00:00:00Z","playCount":0}]}"""
        assertEquals(listOf("x1"), NavidromeClient.parseHistory(rows).map { it.id })
        assertEquals(61, NavidromeClient.parseHistory(rows).single().duration)
        assertEquals(emptyList(), NavidromeClient.parseHistory("not json").map { it.id })
    }

    @Test
    fun `the app reads the history with the password kept from the sign-in`() = FakeSubsonic().use { fake ->
        fake.navidromeHistory = history
        fun app(sealed: String?): AppController {
            val store = SettingsStore(Files.createTempDirectory("platter-test").resolve("settings.json"))
            store.update {
                withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
                sealedPassword = sealed
            }
            return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Default), vlcArgs = listOf("--aout=dummy"), deezerCatalogUrl = fake.url + "deezer/")
        }
        val withPassword = app(Secrets.seal("pw"))
        assertEquals(history.take(3), runBlocking { withPassword.recentlyPlayedSongs(0, 3) }?.map { it.id })
        withPassword.shutdown()

        val without = app(null)
        assertNull(runBlocking { without.recentlyPlayedSongs(0, 3) })
        without.shutdown()
        assertTrue(fake.navidromeLogins.all { it == "pw" })
    }
}
