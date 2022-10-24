package eu.kanade.tachiyomi.animeextension.de.twoixtwo

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class twoixtwo : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "2ix2"

    override val baseUrl = "https://www.2ix2.com"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.filmcontent div.moviefilm"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/meist-gelikte-tv-sender/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.movief a").attr("href"))
        anime.thumbnail_url = element.select("a img").attr("src")
        anime.title = element.select("div.movief a").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = document.select("div.filmaltiaciklama h2").text()
            .substringBefore(" Live")
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
        episodeList.add(episode)
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val script = document.select("div.filmicerik script[type]").toString()
        val quality = "LIVE"
        val videoUrl = script.substringAfter("file: \"").substringBefore("\",")
        videoList.add(Video(videoUrl, quality, videoUrl))
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", "Voe")
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (hoster?.let { video.quality.contains(it) } == true) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (hoster?.let { video.quality.contains(it) } == true) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        return newList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.filmaltiimg img").attr("src")
        anime.title = document.select("div.filmaltiaciklama h2").text()
            .substringBefore(" Live")
        // anime.genre = document.select("#detail-content-list > li:nth-child(2) > span").joinToString(", ") { it.text() }
        // anime.description = document.select("#detail-content-list > li:nth-child(3) > span").text()
        // anime.author = document.select("#detail-content-list > li:nth-child(4) > span").joinToString(", ") { it.text() }
        // anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Voe", "Streamtape", "Evoload")
            entryValues = arrayOf("https://voe.sx", "https://streamtape.com", "https://evoload.io")
            setDefaultValue("https://voe.sx")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Voe", "Streamtape", "Evoload")
            entryValues = arrayOf("voe", "stape", "evo")
            setDefaultValue(setOf("voe", "stape", "evo"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
