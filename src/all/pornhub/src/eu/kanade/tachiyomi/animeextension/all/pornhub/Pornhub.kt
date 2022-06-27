package eu.kanade.tachiyomi.animeextension.all.pornhub

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Pornhub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pornhub"

    override val baseUrl = "https://pornhub.com"

    override val lang = "all"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "ul.nf-videos.videos.search-video-thumbs li.pcVideoListItem.js-pop.videoblock.videoBox"

    override fun popularAnimeRequest(page: Int): Request = GET("https://es.pornhub.com/video?o=tr&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            "$baseUrl${element.select("div.wrap div.phimage a").attr("href")}"
        )

        anime.title = element.select("div.wrap div.thumbnail-info-wrapper.clearfix span.title a").text()
        anime.thumbnail_url = element.select("div.wrap div.phimage a img").attr("data-thumb_url")

        Log.i("bruh", "${element.select("div.wrap div.phimage a img")}")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wrapper"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val jsoup = response.asJsoup()

        val episode = SEpisode.create().apply {
            name = "Video"
            date_upload = System.currentTimeMillis()
        }
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        Log.i("bruh", "aaaa")
        val url = response.request.url.toString()
        val videoList = mutableListOf<Video>()
        // credits to: https://github.com/Joel2B
        val document = Jsoup.connect("https://appsdev.cyou/xv-ph-rt/api/?data=$url").ignoreContentType(true).get()
        val jsonObject = JSONObject(document.select("body").text())["hls"]
        val jsonUrls = JSONObject(jsonObject.toString())

        val url1080 = jsonUrls["1080p"].toString().replace("amp;", "")
        val url720 = jsonUrls["720p"].toString().replace("amp;", "")
        val url480 = jsonUrls["480p"].toString().replace("amp;", "")
        val url240 = jsonUrls["240p"].toString().replace("amp;", "")

        if (jsonUrls["1080p"] != "") {
            videoList.add(Video(url1080, "1080p", url1080, null))
        }
        if (jsonUrls["720p"] != "") {
            videoList.add(Video(url720, "720p", url720, null))
        }
        if (jsonUrls["480p"] != "") {
            videoList.add(Video(url480, "480p", url480, null))
        }
        if (jsonUrls["240p"] != "") {
            videoList.add(Video(url240, "240p", url240, null))
        }

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/video/search?search=$query&page=$page", headers)
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "ul.videos.search-video-thumbs li.pcVideoListItem.js-pop.videoblock.videoBox"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.video-wrapper div.title-container.translate h1.title.translate span.inlineFree").text()
        anime.author = document.select("div.userInfo div.usernameWrap.clearfix span.usernameBadgesWrapper a.bolded").text()
        anime.description = "views: " + document.select("div.ratingInfo div.views span.count").text()
        anime.genre = document.select("div.video-info-row div.categoriesWrapper a.item").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "240p")
            entryValues = arrayOf("1080p", "720p", "480p", "240p")
            setDefaultValue("1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
