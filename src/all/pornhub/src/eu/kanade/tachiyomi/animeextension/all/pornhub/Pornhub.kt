package eu.kanade.tachiyomi.animeextension.all.pornhub

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.pornhub.extractors.PhCdnExtractor
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

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
        anime.title = fromHtml(element.select("div.wrap div.thumbnail-info-wrapper.clearfix span.title a").text()).toString()
        anime.thumbnail_url = element.select("div.wrap div.phimage a img").attr("src").ifEmpty {
            element.select("div.wrap div.phimage a img").attr("data-mediumthumb")
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wrapper"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]").data()
        val jsonData = json.decodeFromString<VideoDetail>(jsonString)
        val epDate = try {
            val dateParts = jsonData.uploadDate.toString().split("-")
            SimpleDateFormat("yyyy-MM-dd").parse("${dateParts[0]}-${dateParts[1]}-${dateParts[2]}")
        } catch (e: Exception) { null }
        val episode = SEpisode.create()
        episode.name = "Video"
        if (epDate != null) episode.date_upload = epDate.time
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    @OptIn(ExperimentalSerializationApi::class)
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return PhCdnExtractor(client).videoFromUrl(url)
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "480p")
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
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]").data()
        val jsonData = json.decodeFromString<VideoDetail>(jsonString)

        anime.title = fromHtml(jsonData.name.toString()).toString()
        anime.author = jsonData.author.toString()
        anime.thumbnail_url = jsonData.thumbnailUrl
        anime.description = fromHtml(jsonData.description.toString()).toString()
        anime.genre = document.select("div.video-info-row div.categoriesWrapper a.item").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    private fun fromHtml(html: String?): Spanned? {
        return if (html == null) SpannableString("")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        else Html.fromHtml(html)
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
            setDefaultValue("480p")
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
    data class VideoDetail(
        @SerialName("@context") var context: String? = null,
        @SerialName("@type") var type: String? = null,
        @SerialName("name") var name: String? = null,
        @SerialName("embedUrl") var embedUrl: String? = null,
        @SerialName("duration") var duration: String? = null,
        @SerialName("thumbnailUrl") var thumbnailUrl: String? = null,
        @SerialName("uploadDate") var uploadDate: String? = null,
        @SerialName("description") var description: String? = null,
        @SerialName("author") var author: String? = null,
        @SerialName("interactionStatistic") var interactionStatistic: ArrayList<InteractionStatistic> = arrayListOf()
    )

    @Serializable
    data class InteractionStatistic(
        @SerialName("@type") var type: String? = null,
        @SerialName("interactionType") var interactionType: String? = null,
        @SerialName("userInteractionCount") var userInteractionCount: String? = null
    )
}
