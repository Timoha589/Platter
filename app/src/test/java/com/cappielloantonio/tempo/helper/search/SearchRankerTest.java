package com.cappielloantonio.tempo.helper.search;

import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;
import com.google.gson.Gson;
import com.platter.search.TrackIndex;
import com.platter.search.TrackSearch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearchRankerTest {
    private final Gson gson = new Gson();

    @Test
    public void exactArtistBeatsStarredTributeBand() {
        Child tribute = song("tribute", "Hello", "Adele Tribute Band");
        tribute.setStarred(new Date());
        Child original = song("original", "Hello", "Adele");
        SearchResult3 response = new SearchResult3();
        response.setSongs(Arrays.asList(tribute, original));
        RankedResults ranked = SearchRanker.rank(QueryVariants.of("Hello Adele"),
                Collections.singletonList(response), LibrarySignals.empty(), null, Collections.emptyList());

        assertEquals(Arrays.asList(original, tribute), ranked.getSongs());
        assertEquals("original", ranked.getTop().getSong().getId());
        assertTrue(ranked.hasStrongMatch());
    }

    @Test
    public void literalTitleBeatsCorrection() {
        Child correction = song("typo", "Halt", "Other");
        Child literal = song("wanted", "Halo Theme", "Martin O'Donnell");
        TrackIndex<Child> index = new TrackIndex<>(Arrays.asList(correction, literal), Child::getId, Child::getTitle,
                Child::artistLine, Child::getArtist, Child::getAlbum);
        RankedResults ranked = SearchRanker.rank(QueryVariants.of("Halo"), Collections.emptyList(), LibrarySignals.empty(),
                null, index.find("Halo", EditDistance::within));

        assertEquals(Arrays.asList(literal, correction), ranked.getSongs());
        assertEquals("wanted", ranked.getTop().getSong().getId());
    }

    @Test
    public void retryKeepsTheFirstResponseButRanksAgainstOriginalQuery() {
        SearchResult3 first = new SearchResult3();
        first.setSongs(Collections.singletonList(song("noise", "Completely Different", "Other")));
        String query = "Love in the Bottle";
        List<QueryVariants.Variant> variants = Collections.singletonList(QueryVariants.of(query).get(0));
        assertFalse(SearchRanker.rank(variants, Collections.singletonList(first), LibrarySignals.empty(), null,
                Collections.emptyList()).hasStrongMatch());

        SearchResult3 retry = new SearchResult3();
        retry.setSongs(Collections.singletonList(song("wanted", "Love in a Bottle", "Artist")));
        RankedResults ranked = SearchRanker.rank(Arrays.asList(variants.get(0), QueryVariants.Variant.guessed("Love Bottle")),
                Arrays.asList(first, retry), LibrarySignals.empty(), query, Collections.emptyList());
        assertEquals(2, ranked.getSongs().size());
        assertEquals("wanted", ranked.getTop().getSong().getId());
    }

    @Test
    public void twoCharacterArtistCanBeTheTopResult() {
        ArtistID3 artist = new ArtistID3();
        artist.setId("artist-u2");
        artist.setName("U2");
        SearchResult3 response = new SearchResult3();
        response.setArtists(Collections.singletonList(artist));
        response.setSongs(Collections.singletonList(song("other", "Track by U2", "Other")));

        RankedResults ranked = SearchRanker.rank(QueryVariants.of("U2"), Collections.singletonList(response), LibrarySignals.empty());

        assertEquals("artist-u2", ranked.getTop().getArtist().getId());
    }

    @Test
    public void oneCharacterKeepsOnlyExactTitlesAndCanPagePastBroadServerHits() {
        Child exact = song("exact", "7", "Artist");
        SearchResult3 response = new SearchResult3();
        response.setSongs(Arrays.asList(song("prefix", "7 Days", "Artist"), song("punctuated", "7!", "Artist"), exact));
        ArtistID3 artist = new ArtistID3();
        artist.setId("artist");
        artist.setName("Studio 7");
        response.setArtists(Collections.singletonList(artist));
        AlbumID3 album = new AlbumID3();
        album.setId("album");
        album.setName("Part 7");
        response.setAlbums(Collections.singletonList(album));

        RankedResults ranked = SearchRanker.rank(QueryVariants.of(" 7 "), Collections.singletonList(response), LibrarySignals.empty());

        assertEquals(Collections.singletonList(exact), ranked.getSongs());
        assertEquals("exact", ranked.getTop().getSong().getId());
        assertTrue(ranked.getArtists().isEmpty());
        assertTrue(ranked.getAlbums().isEmpty());
        TrackSearch query = new TrackSearch("7");
        assertEquals("7", query.phrase(TrackSearch.PAGE_SIZE, false));
        assertNull(query.phrase(TrackSearch.PAGE_SIZE - 1, true));
        assertFalse(query.allowsCorrections());
        assertEquals(1, QueryVariants.of("Я").size());
    }

    @Test
    public void punctuationTitleIsSearchableInTheLocalIndex() {
        Child symbol = song("exact", "!", "Artist");
        TrackIndex<Child> index = new TrackIndex<>(Arrays.asList(song("other", "! Live", "Artist"), symbol),
                Child::getId, Child::getTitle, Child::artistLine, Child::getArtist, Child::getAlbum);

        RankedResults ranked = SearchRanker.rank(QueryVariants.of("!"), Collections.emptyList(), LibrarySignals.empty(), null,
                index.find("!", EditDistance::within));

        assertEquals(Collections.singletonList(symbol), ranked.getSongs());
        assertEquals("exact", ranked.getTop().getSong().getId());
    }

    private Child song(String id, String title, String artist) {
        Child child = gson.fromJson("{\"id\":\"" + id + "\"}", Child.class);
        child.setTitle(title);
        child.setArtist(artist);
        return child;
    }
}
