package com.cappielloantonio.tempo.util

import android.content.Context
import androidx.annotation.StringRes
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.subsonic.models.Genre
import java.util.Locale

/**
 * What a genre is called in the app's language, and a line on what it is.
 *
 * A genre is whatever the server's tags call it: "Rock" from one release,
 * "Рок" from the next, "Hip Hop" or "hip-hop". Each spelling is looked up with
 * case, ё and everything but letters and digits set aside, so all of those
 * find the same entry. A genre not in the table keeps the server's name and
 * has no description.
 */
object GenreNames {
    private class Entry(@StringRes val label: Int, @StringRes val about: Int)

    private val entries = HashMap<String, Entry>()

    private fun add(@StringRes label: Int, @StringRes about: Int, vararg aliases: String) {
        val entry = Entry(label, about)
        for (alias in aliases) entries[alias] = entry
    }

    // One line per genre in res/values*/genres.xml. The aliases are written
    // the way normalize() leaves them, and no alias may appear twice.
    init {
        add(R.string.genre_pop, R.string.genre_pop_about, "pop", "поп", "popmusic", "попмузыка")
        add(R.string.genre_rock, R.string.genre_rock_about, "rock", "рок", "rockmusic", "рокмузыка")
        add(R.string.genre_alternative, R.string.genre_alternative_about, "alternative", "alternativemusic", "альтернатива", "альтернативнаямузыка")
        add(R.string.genre_alternative_rock, R.string.genre_alternative_rock_about, "alternativerock", "altrock", "альтернативныйрок")
        add(R.string.genre_electronic, R.string.genre_electronic_about, "electronic", "electronica", "electronicmusic", "электроника", "электроннаямузыка")
        add(R.string.genre_dance, R.string.genre_dance_about, "dance", "dancemusic", "танцевальная", "танцевальнаямузыка")
        add(R.string.genre_club, R.string.genre_club_about, "club", "clubmusic", "клубная", "клубнаямузыка")
        add(R.string.genre_games, R.string.genre_games_about, "games", "игры", "videogames", "videogamemusic", "gamemusic", "музыкаизвидеоигр", "музыкаизигр")
        add(R.string.genre_film, R.string.genre_film_about, "films", "film", "фильмы", "кино", "filmsoundtracks", "moviesoundtracks", "саундтрекикфильмам", "музыкаизфильмов")
        add(R.string.genre_tv, R.string.genre_tv_about, "tvsoundtracks", "tvmusic", "саундтрекиксериалам", "музыкаизсериалов")
        add(R.string.genre_soundtrack, R.string.genre_soundtrack_about, "soundtrack", "soundtracks", "ost", "саундтрек", "саундтреки")
        add(R.string.genre_musicals, R.string.genre_musicals_about, "musical", "musicals", "мюзикл", "мюзиклы")
        add(R.string.genre_disney, R.string.genre_disney_about, "disney", "дисней")
        add(R.string.genre_asian, R.string.genre_asian_about, "asian", "asianmusic", "азиатская", "азиатскаямузыка")
        add(R.string.genre_jpop, R.string.genre_jpop_about, "jpop", "джейпоп", "японскийпоп")
        add(R.string.genre_kpop, R.string.genre_kpop_about, "kpop", "кейпоп", "корейскийпоп")
        add(R.string.genre_vocaloid, R.string.genre_vocaloid_about, "vocaloid", "вокалоид", "вокалоиды")
        add(R.string.genre_utaite, R.string.genre_utaite_about, "utaite", "утаите", "утаитэ")
        add(R.string.genre_indian, R.string.genre_indian_about, "indian", "indianmusic", "bollywood", "индийская", "индийскаямузыка", "болливуд")
        add(R.string.genre_african, R.string.genre_african_about, "african", "africanmusic", "afrobeats", "африканская", "африканскаямузыка")
        add(R.string.genre_brazilian, R.string.genre_brazilian_about, "brazilian", "brazilianmusic", "бразильская", "бразильскаямузыка")
        add(R.string.genre_latin, R.string.genre_latin_about, "latin", "latino", "latinmusic", "латино", "латиноамериканскаямузыка")
        add(R.string.genre_bolero, R.string.genre_bolero_about, "bolero", "болеро")
        add(R.string.genre_dutch, R.string.genre_dutch_about, "nederlandstaligemuziek", "nederlandstalig", "dutch", "нидерландская")
        add(R.string.genre_international_pop, R.string.genre_international_pop_about, "internationalpop", "международнаяпопмузыка", "зарубежнаяпопмузыка", "зарубежныйпоп")
        add(R.string.genre_rap, R.string.genre_rap_about, "rap", "рэп", "реп")
        add(R.string.genre_hiphop, R.string.genre_hiphop_about, "hiphop", "хипхоп")
        add(R.string.genre_east_coast_rap, R.string.genre_east_coast_rap_about, "eastcoastrap", "eastcoasthiphop", "рэпвосточногопобережья")
        add(R.string.genre_dirty_south, R.string.genre_dirty_south_about, "dirtysouth", "southernhiphop", "дертисаус")
        add(R.string.genre_grime, R.string.genre_grime_about, "grime", "грайм")
        add(R.string.genre_rnb, R.string.genre_rnb_about, "rb", "rnb", "randb", "rhythmandblues", "contemporaryrb", "contemporaryrnb", "ритмнблюз", "ритмэндблюз", "современныйритмэндблюз", "современныйритмнблюз")
        add(R.string.genre_soul, R.string.genre_soul_about, "soul", "соул")
        add(R.string.genre_soul_funk, R.string.genre_soul_funk_about, "soulfunk", "soulandfunk", "соулифанк", "соулфанк")
        add(R.string.genre_contemporary_soul, R.string.genre_contemporary_soul_about, "contemporarysoul", "neosoul", "современныйсоул", "неосоул")
        add(R.string.genre_old_school_soul, R.string.genre_old_school_soul_about, "oldschoolsoul", "classicsoul", "соулстаройшколы")
        add(R.string.genre_funk, R.string.genre_funk_about, "funk", "фанк")
        add(R.string.genre_indie, R.string.genre_indie_about, "indie", "инди")
        add(R.string.genre_indie_rock, R.string.genre_indie_rock_about, "indierock", "индирок")
        add(R.string.genre_indie_pop, R.string.genre_indie_pop_about, "indiepop", "индипоп")
        add(R.string.genre_pop_rock, R.string.genre_pop_rock_about, "poprock", "попрок")
        add(R.string.genre_hard_rock, R.string.genre_hard_rock_about, "hardrock", "хардрок")
        add(R.string.genre_rock_and_roll, R.string.genre_rock_and_roll_about, "rocknroll", "rockandroll", "rockroll", "рокнролл")
        add(R.string.genre_rockabilly, R.string.genre_rockabilly_about, "rockabilly", "рокабилли")
        add(R.string.genre_psychedelic, R.string.genre_psychedelic_about, "psychedelic", "psychedelia", "психоделика", "психоделическаямузыка")
        add(R.string.genre_psychedelic_rock, R.string.genre_psychedelic_rock_about, "psychedelicrock", "психоделическийрок")
        add(R.string.genre_new_wave, R.string.genre_new_wave_about, "newwave", "ньювейв")
        add(R.string.genre_punk, R.string.genre_punk_about, "punk", "punkrock", "панк", "панкрок")
        add(R.string.genre_post_punk, R.string.genre_post_punk_about, "postpunk", "постпанк")
        add(R.string.genre_metal, R.string.genre_metal_about, "metal", "heavymetal", "метал", "металл", "хэвиметал")
        add(R.string.genre_electropop, R.string.genre_electropop_about, "electropop", "электропоп")
        add(R.string.genre_synthpop, R.string.genre_synthpop_about, "synthpop", "синтипоп")
        add(R.string.genre_electro_rock, R.string.genre_electro_rock_about, "electrorock", "электророк")
        add(R.string.genre_techno, R.string.genre_techno_about, "techno", "техно")
        add(R.string.genre_house, R.string.genre_house_about, "house", "хаус")
        add(R.string.genre_trance, R.string.genre_trance_about, "trance", "транс")
        add(R.string.genre_dubstep, R.string.genre_dubstep_about, "dubstep", "дабстеп")
        add(R.string.genre_drum_and_bass, R.string.genre_drum_and_bass_about, "drumandbass", "drumnbass", "dnb", "драмнбейс", "драмэндбейс")
        add(R.string.genre_breakcore, R.string.genre_breakcore_about, "breakcore", "брейккор")
        add(R.string.genre_ambient, R.string.genre_ambient_about, "ambient", "эмбиент")
        add(R.string.genre_lounge, R.string.genre_lounge_about, "lounge", "лаунж")
        add(R.string.genre_trip_hop, R.string.genre_trip_hop_about, "triphop", "трипхоп")
        add(R.string.genre_chill_out, R.string.genre_chill_out_about, "chillout", "chill", "чилаут")
        add(R.string.genre_lofi, R.string.genre_lofi_about, "lofi", "lofihiphop", "лоуфай")
        add(R.string.genre_disco, R.string.genre_disco_about, "disco", "диско")
        add(R.string.genre_reggae, R.string.genre_reggae_about, "reggae", "регги")
        add(R.string.genre_dub, R.string.genre_dub_about, "dub", "даб")
        add(R.string.genre_ska, R.string.genre_ska_about, "ska", "ска")
        add(R.string.genre_dancehall, R.string.genre_dancehall_about, "dancehall", "дэнсхолл", "дансхолл")
        add(R.string.genre_ragga, R.string.genre_ragga_about, "ragga", "raggamuffin", "рагга")
        add(R.string.genre_blues, R.string.genre_blues_about, "blues", "блюз")
        add(R.string.genre_chicago_blues, R.string.genre_chicago_blues_about, "chicagoblues", "чикагскийблюз")
        add(R.string.genre_jazz, R.string.genre_jazz_about, "jazz", "джаз")
        add(R.string.genre_instrumental_jazz, R.string.genre_instrumental_jazz_about, "instrumentaljazz", "инструментальныйджаз")
        add(R.string.genre_folk, R.string.genre_folk_about, "folk", "фолк", "folkmusic")
        add(R.string.genre_country, R.string.genre_country_about, "country", "кантри")
        add(R.string.genre_singer_songwriter, R.string.genre_singer_songwriter_about, "singersongwriter", "singerandsongwriter", "авторисполнитель", "бардовская", "бардовскаяпесня")
        add(R.string.genre_chanson, R.string.genre_chanson_about, "chanson", "шансон")
        add(R.string.genre_classical, R.string.genre_classical_about, "classical", "classicalmusic", "классика", "классическаямузыка")
        add(R.string.genre_baroque, R.string.genre_baroque_about, "baroque", "барокко")
        add(R.string.genre_symphonic, R.string.genre_symphonic_about, "symphony", "symphonic", "симфония", "симфоническаямузыка")
        add(R.string.genre_opera, R.string.genre_opera_about, "opera", "опера")
        add(R.string.genre_contemporary, R.string.genre_contemporary_about, "contemporary", "contemporarymusic", "современная", "современнаямузыка")
        add(R.string.genre_kids, R.string.genre_kids_about, "kids", "children", "childrensmusic", "детская", "детскаямузыка", "детскиепесенки", "детскиепесни")
        add(R.string.genre_comedy, R.string.genre_comedy_about, "comedy", "humor", "humour", "комедия", "юмор")
        add(R.string.genre_sports, R.string.genre_sports_about, "sport", "sports", "спорт")
    }

    private fun normalize(name: String): String =
            name.lowercase(Locale.ROOT).replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun entry(name: String?): Entry? = name?.let { entries[normalize(it)] }

    @JvmStatic
    fun label(context: Context, name: String?): String =
            entry(name)?.let { context.getString(it.label) } ?: name.orEmpty()

    @JvmStatic
    fun about(context: Context, name: String?): String? =
            entry(name)?.let { context.getString(it.about) }

    /**
     * One genre per name on screen. The same genre tagged in two languages
     * would otherwise be two tiles with one label; they become one, counting
     * both, and remember every server name it stands for (Genre.sources), which
     * is what its page loads.
     */
    @JvmStatic
    fun merge(genres: List<Genre>): List<Genre> {
        val groups = LinkedHashMap<Any, MutableList<Genre>>()

        for (genre in genres) {
            val name = genre.genre?.takeIf { it.isNotBlank() } ?: continue
            val key: Any = entry(name) ?: normalize(name).ifEmpty { name }
            groups.getOrPut(key) { ArrayList() }.add(genre)
        }

        return groups.values.map { group ->
            if (group.size == 1) return@map group[0]

            val biggestFirst = group.sortedByDescending { it.songCount }
            Genre().apply {
                genre = biggestFirst[0].genre
                songCount = group.sumOf { it.songCount }
                albumCount = group.sumOf { it.albumCount }
                sources = biggestFirst.mapNotNullTo(ArrayList()) { it.genre }
            }
        }
    }
}
