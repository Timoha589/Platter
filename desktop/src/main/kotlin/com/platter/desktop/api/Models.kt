package com.platter.desktop.api

import com.google.gson.annotations.SerializedName

/*
 * Subsonic / OpenSubsonic response shapes, cut down from the Android app's
 * subsonic/models to what the desktop client reads.
 *
 * Every field is nullable and defaulted to nothing: Gson builds these through
 * Unsafe, so Kotlin defaults never run, and a server that omits a field would
 * otherwise leave a non-null property holding null. Dates stay strings - the
 * only question asked of them is "is it set" (starred), and servers disagree
 * on the exact ISO format.
 */

class ApiResponse {
    @SerializedName("subsonic-response")
    var subsonicResponse: SubsonicResponse? = null
}

class SubsonicResponse {
    var status: String? = null
    var version: String? = null
    var type: String? = null
    var serverVersion: String? = null
    var openSubsonic: Boolean? = null
    var error: ApiError? = null

    var artists: ArtistsResult? = null
    var artist: Artist? = null
    var album: Album? = null
    var song: Song? = null
    var albumList2: AlbumList? = null
    var randomSongs: SongList? = null
    var searchResult3: SearchResult? = null
    var starred2: SearchResult? = null
    var playlists: PlaylistList? = null
    var playlist: Playlist? = null
    var lyricsList: LyricsList? = null
    var lyrics: PlainLyrics? = null
    var topSongs: SongList? = null
    var similarSongs2: SongList? = null
    var songsByGenre: SongList? = null
    var artistInfo2: ArtistInfo? = null
    var genres: GenreList? = null
    var shares: ShareList? = null
    var scanStatus: ScanStatus? = null
    var playQueue: SavedQueue? = null

    /** getPlayQueueByIndex answers under its own key (the indexBasedQueue extension). */
    var playQueueByIndex: SavedQueue? = null
    var podcasts: PodcastList? = null
    var newestPodcasts: EpisodeList? = null
    var internetRadioStations: RadioList? = null
    var openSubsonicExtensions: List<OpenSubsonicExtension>? = null
}

class OpenSubsonicExtension {
    var name: String? = null
}

/** The queue the server keeps for the user, so another device can pick it up. */
class SavedQueue {
    @SerializedName("entry")
    var entries: List<Song>? = null

    /** The playing song's id; all a server without indexBasedQueue can say, and wrong for a queue holding a song twice. */
    var current: String? = null
    var currentIndex: Int? = null
    var position: Long? = null
    var changedBy: String? = null

    /** Where playback should resume, or -1: the index the server sent, else the first song with the current id. */
    fun resolvedIndex(): Int {
        val queue = entries.orEmpty()
        if (queue.isEmpty()) return -1
        currentIndex?.let { if (it in queue.indices) return it }
        val id = current ?: return -1
        return queue.indexOfFirst { it.id == id }
    }
}

class PodcastList {
    @SerializedName("channel")
    var channels: List<PodcastChannel>? = null
}

class EpisodeList {
    @SerializedName("episode")
    var episodes: List<PodcastEpisode>? = null
}

class PodcastChannel {
    var id: String? = null
    var url: String? = null
    var title: String? = null
    var description: String? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null
    var status: String? = null
    var errorMessage: String? = null

    @SerializedName("episode")
    var episodes: List<PodcastEpisode>? = null
}

class PodcastEpisode {
    var id: String? = null

    /** The id the audio is streamed by; an episode has one only once the server has downloaded it. */
    var streamId: String? = null
    var channelId: String? = null
    var title: String? = null
    var description: String? = null
    var artist: String? = null
    var album: String? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null
    var duration: Int? = null
    var publishDate: String? = null
    var status: String? = null

    val isDownloaded: Boolean get() = status == "completed" && !streamId.isNullOrEmpty()
}

class RadioList {
    @SerializedName("internetRadioStation")
    var stations: List<RadioStation>? = null
}

class RadioStation {
    var id: String? = null
    var name: String? = null
    var streamUrl: String? = null

    @SerializedName("homePageUrl")
    var homePageUrl: String? = null
}

/** `getArtistInfo2`: what the server's agents (Last.fm and the like) know about an artist. */
class ArtistInfo {
    var biography: String? = null
    var lastFmUrl: String? = null

    @SerializedName("similarArtist")
    var similarArtists: List<Artist>? = null
}

class GenreList {
    @SerializedName("genre")
    var genres: List<Genre>? = null
}

class Genre {
    /** The genre's name; OpenSubsonic calls the field `value`. */
    var value: String? = null
    var songCount: Int? = null
    var albumCount: Int? = null
}

class ShareList {
    @SerializedName("share")
    var shares: List<Share>? = null
}

class Share {
    var id: String? = null
    var url: String? = null
}

class ScanStatus {
    var scanning: Boolean? = null
    var count: Long? = null
}

/** OpenSubsonic `getLyricsBySongId`: a song can carry several layers - sung words, a translation, a pronunciation guide. */
class LyricsList {
    var structuredLyrics: List<StructuredLyrics>? = null
}

class StructuredLyrics {
    var displayArtist: String? = null
    var displayTitle: String? = null
    var lang: String? = null

    /** Added to the playback position before the lines are matched against it. */
    var offset: Int? = null
    var synced: Boolean? = null

    /** songLyrics v2: `main`, `translation` or `pronunciation`; absent on a v1 server, where every layer is the main one. */
    var kind: String? = null
    var line: List<LyricLine>? = null

    companion object {
        const val KIND_MAIN = "main"
        const val KIND_PRONUNCIATION = "pronunciation"
    }
}

class LyricLine {
    /** Milliseconds; absent on lyrics that are not timed. */
    var start: Int? = null
    var value: String? = null
}

/** The old `getLyrics`: plain text, found by artist and title. */
class PlainLyrics {
    var value: String? = null
}

class ApiError {
    var code: Int? = null
    var message: String? = null
}

class ArtistsResult {
    @SerializedName("index")
    var indices: List<ArtistIndex>? = null
}

class ArtistIndex {
    var name: String? = null

    @SerializedName("artist")
    var artists: List<Artist>? = null
}

class AlbumList {
    @SerializedName("album")
    var albums: List<Album>? = null
}

class SongList {
    @SerializedName("song")
    var songs: List<Song>? = null
}

class PlaylistList {
    @SerializedName("playlist")
    var playlists: List<Playlist>? = null
}

/** search3 and getStarred2 answer with the same three lists. */
class SearchResult {
    @SerializedName("artist")
    var artists: List<Artist>? = null

    @SerializedName("album")
    var albums: List<Album>? = null

    @SerializedName("song")
    var songs: List<Song>? = null
}

/** The Android app's `Child`: a song, as far as this client cares. */
class Song {
    var id: String? = null
    var title: String? = null
    var album: String? = null
    var albumId: String? = null
    var artist: String? = null
    var artistId: String? = null
    var displayArtist: String? = null
    var track: Int? = null
    var discNumber: Int? = null
    var year: Int? = null
    var genre: String? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null
    var duration: Int? = null

    @SerializedName("bitRate")
    var bitrate: Int? = null
    var suffix: String? = null
    var contentType: String? = null
    var size: Long? = null
    var starred: String? = null
    var userRating: Int? = null
    var playCount: Long? = null
    var played: String? = null
    var path: String? = null
    var bitDepth: Int? = null
    var samplingRate: Int? = null
    var channelCount: Int? = null

    /** Set by this client, never by a server: what kind of thing is queued - null for music, else `podcast` or `radio`. */
    @Transient
    var kind: String? = null

    /** Where a station is heard; music and podcasts are streamed by id. */
    @Transient
    var streamUrl: String? = null

    val isMusic: Boolean get() = kind == null
    val isLive: Boolean get() = kind == KIND_RADIO

    fun artistLine(): String? = displayArtist?.takeIf { it.isNotBlank() } ?: artist

    companion object {
        const val KIND_PODCAST = "podcast"
        const val KIND_RADIO = "radio"
    }
}

/** The Android app's `AlbumID3`; with `songs` filled in this is what getAlbum returns. */
class Album {
    var id: String? = null
    var name: String? = null
    var artist: String? = null
    var artistId: String? = null
    var displayArtist: String? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null
    var songCount: Int? = null
    var duration: Int? = null
    var year: Int? = null
    var genre: String? = null
    var starred: String? = null
    var sortName: String? = null
    var userRating: Int? = null
    var playCount: Long? = null
    var played: String? = null

    /** When the server first saw the album, ISO 8601: it sorts as text, which is all "newest first" needs. */
    var created: String? = null

    @SerializedName("song")
    var songs: List<Song>? = null

    fun artistLine(): String? = displayArtist?.takeIf { it.isNotBlank() } ?: artist
}

/** The Android app's `ArtistID3`; with `albums` filled in this is what getArtist returns. */
class Artist {
    var id: String? = null
    var name: String? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null
    var albumCount: Int? = null
    var starred: String? = null
    var sortName: String? = null
    var userRating: Int? = null

    @SerializedName("album")
    var albums: List<Album>? = null
}

class Playlist {
    var id: String? = null
    var name: String? = null
    var comment: String? = null
    var owner: String? = null
    var songCount: Int? = null
    var duration: Int? = null

    @SerializedName("public")
    var isPublic: Boolean? = null

    @SerializedName("coverArt")
    var coverArtId: String? = null

    @SerializedName("entry")
    var entries: List<Song>? = null
}
