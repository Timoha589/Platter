package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.player.EqPresets
import com.platter.desktop.player.EqSettings
import com.platter.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqualizerTest {
    @Test
    fun `every preset has ten bands within what a slider can ask for, and flat is level`() {
        EqPresets.all.forEach { (name, gains) ->
            assertEquals(EqSettings.BANDS, gains.size, name)
            assertTrue(gains.all { it in -EqSettings.RANGE_DB..EqSettings.RANGE_DB }, name)
        }
        assertTrue(EqPresets.all.getValue("Flat").all { it == 0f })
        assertEquals(EqPresets.all.size, EqPresets.all.keys.map { it.lowercase() }.toSet().size)
        assertEquals(EqSettings.BANDS, EqSettings.FREQUENCIES.size)
    }

    @Test
    fun `the preset a set of gains is can be told, so the dialog can show it chosen`() {
        assertEquals("Rock", EqPresets.nameOf(EqPresets.all.getValue("Rock")))
        assertEquals("Flat", EqPresets.nameOf(List(10) { 0f }))
        assertNull(EqPresets.nameOf(List(10) { 1f }), "bent by hand")
        assertNull(EqPresets.nameOf(listOf(0f, 0f)))
    }

    @Test
    fun `gains are always ten, whatever was saved`() {
        assertEquals(List(10) { 0f }, EqSettings().gains())
        assertEquals(listOf(3f, 2f) + List(8) { 0f }, EqSettings(bands = listOf(3f, 2f)).gains())
        assertEquals(10, EqSettings(bands = List(14) { 1f }).gains().size)
    }

    // --- in the app -----------------------------------------------------------------------------------

    private fun app(fake: FakeSubsonic, store: SettingsStore): AppController {
        store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy"))
    }

    @Test
    fun `choosing a preset switches the equalizer on and keeps it for next time`() = FakeSubsonic().use { fake ->
        val store = SettingsStore(Files.createTempDirectory("platter-eq").resolve("settings.json"))
        val app = app(fake, store)
        try {
            assertFalse(app.equalizer.enabled)
            app.chooseEqPreset("Bass boost")
            assertTrue(app.equalizer.enabled)
            assertEquals(EqPresets.all.getValue("Bass boost"), app.equalizer.bands)

            val saved = store.load()
            assertTrue(saved.eqEnabled)
            assertEquals(EqPresets.all.getValue("Bass boost"), saved.eqBands)

            app.shutdown()
            val again = app(fake, store)
            try {
                assertTrue(again.equalizer.enabled)
                assertEquals(EqPresets.all.getValue("Bass boost"), again.equalizer.bands)
            } finally {
                again.shutdown()
            }
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun `a gain beyond the sliders is brought back, and an unknown preset changes nothing`() = FakeSubsonic().use { fake ->
        val app = app(fake, SettingsStore(Files.createTempDirectory("platter-eq").resolve("settings.json")))
        try {
            app.changeEqualizer(EqSettings(true, preamp = 40f, bands = listOf(99f, -99f)))
            assertEquals(EqSettings.RANGE_DB, app.equalizer.preamp)
            assertEquals(EqSettings.RANGE_DB, app.equalizer.gains()[0])
            assertEquals(-EqSettings.RANGE_DB, app.equalizer.gains()[1])
            assertEquals(10, app.equalizer.bands.size)

            val before = app.equalizer
            app.chooseEqPreset("No such preset")
            assertEquals(before, app.equalizer)
        } finally {
            app.shutdown()
        }
    }

    // --- in libvlc ---------------------------------------------------------------------------------------

    @Test
    fun `the engine takes the equalizer while playing, bends, and takes it out again`() = FakeSubsonic().use { fake ->
        val client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val player = PlayerController(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            { client.streamUrl(it.id!!) },
            { _, _, _ -> },
            vlcArgs = listOf("--aout=dummy"),
            initialEqualizer = EqSettings(true, 3f, EqPresets.all.getValue("Rock")),
        )
        try {
            player.play(listOf(Song().apply { id = "slow-1"; title = "T"; artist = "A"; duration = 100 }))
            waitUntil("playback with the equalizer on") { player.state.value.isPlaying }
            assertTrue(player.equalizerActive, "libvlc took the equalizer the player started with")

            // Bent, switched off, on again - each while the song plays.
            player.setEqualizer(EqSettings(true, -2f, EqPresets.all.getValue("Jazz")))
            Thread.sleep(300)
            assertTrue(player.equalizerActive)
            player.setEqualizer(EqSettings(enabled = false))
            waitUntil("the equalizer to be taken out") { !player.equalizerActive }
            player.setEqualizer(EqSettings(true, 0f, EqPresets.all.getValue("Vocal")))
            waitUntil("the equalizer to go back in") { player.equalizerActive }
            Thread.sleep(500)

            assertTrue(player.state.value.isPlaying, "the music played through every change")
            assertNull(player.state.value.error)
        } finally {
            player.release()
        }
    }
}
