package eu.kanade.tachiyomi.animeextension.all.pornhub

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Pornhub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pornhub"

    override val baseUrl = "https://pornhub.com"

    override val lang = "all"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

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
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wrapper"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

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

    @OptIn(ExperimentalSerializationApi::class)
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val videoList = mutableListOf<Video>()
        // credits to: https://github.com/Joel2B
        val document = client.newCall(GET("https://appsdev.cyou/xv-ph-rt/api/?data=$url")).execute().asJsoup().body().text()
        val jsonResponse = json.decodeFromString<PornApiResponse>(document)

        return listOf(
            Video(jsonResponse.hls!!.all!!,"HLS: ALL",jsonResponse.hls!!.all),
            Video(jsonResponse.hls!!.low!!,"HLS: LOW",jsonResponse.hls!!.low),
            Video(jsonResponse.hls!!.hd!!,"HLS: HD",jsonResponse.hls!!.hd),
            Video(jsonResponse.hls!!.fhd!!,"HLS: FHD",jsonResponse.hls!!.fhd),
            Video(jsonResponse.mp4!!.low!!,"MP4: LOW",jsonResponse.mp4!!.low),
            Video(jsonResponse.mp4!!.sd!!,"MP4: SD",jsonResponse.mp4!!.sd),
            Video(jsonResponse.mp4!!.hd!!,"MP4: HD",jsonResponse.mp4!!.hd),
            Video(jsonResponse.mp4!!.fhd!!,"MP4: FHD",jsonResponse.mp4!!.fhd)
        ).filter { it.url.isNotBlank() }
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
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

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

    @Serializable
    data class PornApiResponse (
        var hls        : Hls?    = Hls(),
        var mp4        : Mp4?    = Mp4(),
        var thumb      : String? = null,
        var thumbnails : String? = null

    )

    @Serializable
    data class Hls (
        var all   : String? = "",
        @SerialName("1080p" ) var fhd : String? = "",
        @SerialName("720p"  ) var hd  : String? = "",
        @SerialName("480p"  ) var sd  : String? = "",
        @SerialName("240p"  ) var low  : String? = ""

    )

    @Serializable
    data class Mp4 (
    @SerialName("1080p" ) var fhd : String? = "",
    @SerialName("720p"  ) var hd  : String? = "",
    @SerialName("480p"  ) var sd  : String? = "",
    @SerialName("240p"  ) var low  : String? = ""
    )


}
