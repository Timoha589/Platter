package com.platter.desktop

import com.platter.desktop.api.Song
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayQueueTest {
    private fun song(id: String) = Song().apply { this.id = id }
    private fun ids(q: PlayQueue) = q.songs.map { it.id }
    private val abc = PlayQueue().replace(listOf(song("a"), song("b"), song("c")))

    @Test
    fun `replace starts at the chosen track and clamps`() {
        assertEquals("b", PlayQueue().replace(listOf(song("a"), song("b")), 1).current?.id)
        assertEquals("b", PlayQueue().replace(listOf(song("a"), song("b")), 9).current?.id)
        assertNull(PlayQueue().replace(emptyList()).current)
    }

    private val alphabet = PlayQueue().replace(('a'..'j').map { song(it.toString()) })

    @Test
    fun `shuffle keeps what has played and the current track, mixes the rest and remembers the order`() {
        val q = alphabet.jumpTo(2).toggleShuffle()
        assertEquals(true, q.shuffle)
        assertEquals(listOf("a", "b", "c"), ids(q).take(3))
        assertEquals("c", q.current?.id)
        assertEquals(('d'..'j').map { it.toString() }.toSet(), ids(q).drop(3).toSet())
    }

    @Test
    fun `shuffle off puts the order back around the track that is playing`() {
        val shuffled = alphabet.toggleShuffle().jumpTo(4)
        val playing = shuffled.current?.id
        val back = shuffled.toggleShuffle()
        assertEquals(false, back.shuffle)
        assertEquals(('a'..'j').map { it.toString() }, ids(back))
        assertEquals(playing, back.current?.id)
    }

    @Test
    fun `songs added while shuffled follow the rest when shuffle goes off, and removed ones stay gone`() {
        val q = alphabet.toggleShuffle().enqueue(song("x")).let { it.removeAt(it.songs.indexOfFirst { s -> s.id == "e" }) }
        val back = q.toggleShuffle()
        assertEquals(('a'..'j').filter { it != 'e' }.map { it.toString() } + "x", ids(back))
    }

    @Test
    fun `with shuffle on a new list is played shuffled from the song chosen`() {
        val q = alphabet.toggleShuffle().replace(('k'..'t').map { song(it.toString()) }, start = 3)
        assertEquals(true, q.shuffle)
        assertEquals("n", q.current?.id)
        assertEquals(0, q.index)
        assertEquals(('k'..'t').map { it.toString() }.toSet(), ids(q).toSet())
        assertEquals(('k'..'t').map { it.toString() }, ids(q.toggleShuffle()))
    }

    @Test
    fun `shuffle on an empty queue is only a setting`() {
        val q = PlayQueue().toggleShuffle()
        assertEquals(true, q.shuffle)
        assertEquals(true, q.songs.isEmpty())
        assertEquals(false, q.toggleShuffle().shuffle)
    }

    @Test
    fun `play next goes right after the current track, enqueue goes last`() {
        val q = abc.playNext(song("x")).enqueue(song("y"))
        assertEquals(listOf("a", "x", "b", "c", "y"), ids(q))
        assertEquals("a", q.current?.id)
    }

    @Test
    fun `adding to an empty queue makes it the current track`() {
        assertEquals("x", PlayQueue().enqueue(song("x")).current?.id)
    }

    @Test
    fun `removing a track before the current keeps the same track playing`() {
        val q = abc.jumpTo(2).removeAt(0)
        assertEquals(listOf("b", "c"), ids(q))
        assertEquals("c", q.current?.id)
    }

    @Test
    fun `removing the last track while on it moves back`() {
        val q = abc.jumpTo(2).removeAt(2)
        assertEquals("b", q.current?.id)
    }

    @Test
    fun `removing the only track empties the queue`() {
        assertNull(PlayQueue().replace(listOf(song("a"))).removeAt(0).current)
    }

    @Test
    fun `queue ends at the last track with repeat off`() {
        assertNull(abc.jumpTo(2).after(auto = true))
        assertEquals(1, abc.after(auto = true))
    }

    @Test
    fun `repeat all wraps and repeat one stays on automatic advance only`() {
        val all = abc.jumpTo(2).copy(repeat = RepeatMode.All)
        assertEquals(0, all.after(auto = true))

        val one = abc.jumpTo(1).copy(repeat = RepeatMode.One)
        assertEquals(1, one.after(auto = true))
        assertEquals(2, one.after(auto = false))
    }

    @Test
    fun `repeat cycles off, all, one, off`() {
        assertEquals(RepeatMode.All, abc.cycleRepeat().repeat)
        assertEquals(RepeatMode.One, abc.cycleRepeat().cycleRepeat().repeat)
        assertEquals(RepeatMode.Off, abc.cycleRepeat().cycleRepeat().cycleRepeat().repeat)
    }
}
