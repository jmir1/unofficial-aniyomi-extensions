package eu.kanade.tachiyomi.animeextension.all.ted

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Ted : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Ted"

    override val baseUrl = "https://www.ted.com"

    override val lang = "all"

    override val supportsLatest = true

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = document.select("script[type=application/json]").toString()
        val anime = SAnime.create().apply {
            title = doc.substringAfter("\"title\":\"").substringBefore("\",\"")
            author = doc.substringAfter("\"presenterDisplayName\":\"").substringBefore("\",\"")
            genre = doc.substringAfter("\\\"tag\\\":\\\"").substringBefore("\\\",\\\"").split(",")
                .joinToString(separator = ", ", transform = String::capitalize)
            status = SAnime.COMPLETED
            description = doc.substringAfter("\"socialDescription\":\"").substringBefore("\",\"")
        }
        return anime
    }

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div div div.talk-link"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val url = "$baseUrl/talks?".toHttpUrlOrNull()!!.newBuilder()
        for (filter in filterList) {
            when (filter) {
                is TopicFilter -> {
                    val topics = mutableListOf<String>()
                    filter.state.forEach {
                        if (it.state) {
                            topics.add(it.name)
                        }
                    }
                    if (topics.isNotEmpty()) {
                        topics.forEach { url.addQueryParameter("topics[]", it) }
                    }
                }
                is LanguageFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("language", filter.toUriPart())
                    }
                }
                is SortFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("sort", filter.toUriPart())
                    }
                }
                is DurationFilter -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("duration", filter.toUriPart())
                    }
                }
            }
        }
        if (query.isNotEmpty()) url.addQueryParameter("q", query)
        return GET(url.toString())
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/talks?page=$page&sort=relevance")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(
                element.select("div div a").attr("href")
            )
            title = element.select("h4 a.ga-link").text()
            thumbnail_url = element.select("div div a span span span img").attr("src")
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.pagination__next.pagination__flipper.pagination__link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup().select("script[type=application/json]").toString()
        return mutableListOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Video"
                episode_number = 1F
                date_upload = document.substringAfter("\\\"published\\\":").substringBefore("}\"").substringBefore(",\\\"").toLong() * 1000
            }
        )
    }
    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup().select("script[type=application/json]").toString()
        val video = document.substringAfter(",\\\"file\\\":\\\"").substringBefore("\\\"}],")
        return try {
            val trackJson = document.substringAfter("\\\",\\\"metadata\\\":\\\"").substringBefore("\\\"}}")
            val trackJsonResponse = client.newCall(GET(trackJson)).execute().body!!.string()
            val json = json.decodeFromString<JsonObject>(trackJsonResponse)
            val subs2 = mutableListOf<Track>()
            json["subtitles"]?.jsonArray
                ?.map { track ->
                    val trackUrl = track.jsonObject["webvtt"]!!.jsonPrimitive.content
                    val lang = track.jsonObject["name"]!!.jsonPrimitive.content
                    subs2.add(Track(trackUrl, lang))
                } ?: emptyList()
            val subs = subLangOrder(subs2)
            listOf(
                Video(video, "Video", video, subtitleTracks = subs),
            )
        } catch (e: Error) {
            listOf(
                Video(video, "Video", video),
            )
        }
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString(PREF_SUB_KEY, null)
        if (language != null) {
            val newList = mutableListOf<Track>()
            var preferred = 0
            for (track in tracks) {
                if (track.lang == language) {
                    newList.add(preferred, track)
                    preferred++
                } else {
                    newList.add(track)
                }
            }
            return newList
        }
        return tracks
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/talks?page=$page&sort=newest")

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination.pagination-simple li a:contains(Next)"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        LanguageFilter(),
        TopicFilter(topics),
        DurationFilter(),
        SortFilter(),
    )

    private class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("<Select>", ""),
            Pair("Afrikaans", "af"),
            Pair("Albanian", "sq"),
            Pair("Algerian Arabic", "arq"),
            Pair("Amharic", "am"),
            Pair("Arabic", "ar"),
            Pair("Armenian", "hy"),
            Pair("Assamese", "as"),
            Pair("Asturian", "ast"),
            Pair("Azerbaijani", "az"),
            Pair("Basque", "eu"),
            Pair("Belarusian", "be"),
            Pair("Bengali", "bn"),
            Pair("Bislama", "bi"),
            Pair("Bosnian", "bs"),
            Pair("Bulgarian", "bg"),
            Pair("Burmese", "my"),
            Pair("Catalan", "ca"),
            Pair("Cebuano", "ceb"),
            Pair("Chinese, Simplified", "zh-cn"),
            Pair("Chinese, Traditional", "zh-tw"),
            Pair("Chinese, Yue", "zh"),
            Pair("Creole, Haitian", "ht"),
            Pair("Croatian", "hr"),
            Pair("Czech", "cs"),
            Pair("Danish", "da"),
            Pair("Dutch", "nl"),
            Pair("Dzongkha", "dz"),
            Pair("English", "en"),
            Pair("Esperanto", "eo"),
            Pair("Estonian", "et"),
            Pair("Faroese", "fo"),
            Pair("Filipino", "fil"),
            Pair("Finnish", "fi"),
            Pair("French", "fr"),
            Pair("French (Canada)", "fr-ca"),
            Pair("Galician", "gl"),
            Pair("Georgian", "ka"),
            Pair("German", "de"),
            Pair("Greek", "el"),
            Pair("Gujarati", "gu"),
            Pair("Hakha Chin", "cnh"),
            Pair("Hausa", "ha"),
            Pair("Hebrew", "he"),
            Pair("Hindi", "hi"),
            Pair("Hungarian", "hu"),
            Pair("Hupa", "hup"),
            Pair("Icelandic", "is"),
            Pair("Igbo", "ig"),
            Pair("Indonesian", "id"),
            Pair("Ingush", "inh"),
            Pair("Irish", "ga"),
            Pair("Italian", "it"),
            Pair("Japanese", "ja"),
            Pair("Kannada", "kn"),
            Pair("Kazakh", "kk"),
            Pair("Khmer", "km"),
            Pair("Klingon", "tlh"),
            Pair("Korean", "ko"),
            Pair("Kurdish (Central)", "ckb"),
            Pair("Kyrgyz", "ky"),
            Pair("Lao", "lo"),
            Pair("Latgalian", "ltg"),
            Pair("Latin", "la"),
            Pair("Latvian", "lv"),
            Pair("Lithuanian", "lt"),
            Pair("Luxembourgish", "lb"),
            Pair("Macedonian", "mk"),
            Pair("Malagasy", "mg"),
            Pair("Malay", "ms"),
            Pair("Malayalam", "ml"),
            Pair("Maltese", "mt"),
            Pair("Marathi", "mr"),
            Pair("Mauritian Creole", "mfe"),
            Pair("Mongolian", "mn"),
            Pair("Montenegrin", "srp"),
            Pair("Nepali", "ne"),
            Pair("Northern Kurdish (Kurmanji)", "kmr"),
            Pair("Norwegian Bokmal", "nb"),
            Pair("Norwegian Nynorsk", "nn"),
            Pair("Occitan", "oc"),
            Pair("Pashto", "ps"),
            Pair("Persian", "fa"),
            Pair("Polish", "pl"),
            Pair("Portuguese", "pt"),
            Pair("Portuguese, Brazilian", "pt-br"),
            Pair("Punjabi", "pa"),
            Pair("Romanian", "ro"),
            Pair("Russian", "ru"),
            Pair("Rusyn", "ry"),
            Pair("Sardinian", "sc"),
            Pair("Serbian", "sr"),
            Pair("Serbo-Croatian", "sh"),
            Pair("Silesian", "szl"),
            Pair("Sinhala", "si"),
            Pair("Slovak", "sk"),
            Pair("Slovenian", "sl"),
            Pair("Somali", "so"),
            Pair("Spanish", "es"),
            Pair("Swahili", "sw"),
            Pair("Swedish", "sv"),
            Pair("Tagalog", "tl"),
            Pair("Tajik", "tg"),
            Pair("Tamil", "ta"),
            Pair("Tatar", "tt"),
            Pair("Telugu", "te"),
            Pair("Thai", "th"),
            Pair("Tibetan", "bo"),
            Pair("Tunisian Arabic", "aeb"),
            Pair("Turkish", "tr"),
            Pair("Turkmen", "tk"),
            Pair("Ukrainian", "uk"),
            Pair("Urdu", "ur"),
            Pair("Uyghur", "ug"),
            Pair("Uzbek", "uz"),
            Pair("Vietnamese", "vi"),
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class Topic(id: String) : AnimeFilter.CheckBox(id)
    private class TopicFilter(topics: List<Topic>) : AnimeFilter.Group<Topic>("Topics", topics)
    private val topics = listOf(
        Topic("Activism"),
        Topic("Addiction"),
        Topic("Africa"),
        Topic("Aging"),
        Topic("Agriculture"),
        Topic("AI"),
        Topic("AIDS"),
        Topic("Algorithm"),
        Topic("Aliens"),
        Topic("Alzheimer's"),
        Topic("Ancient world"),
        Topic("Animals"),
        Topic("Animation"),
        Topic("Antarctica"),
        Topic("Anthropocene"),
        Topic("Anthropology"),
        Topic("Archaeology"),
        Topic("Architecture"),
        Topic("Art"),
        Topic("Asia"),
        Topic("Asteroid"),
        Topic("Astrobiology"),
        Topic("Astronomy"),
        Topic("Atheism"),
        Topic("Audacious Project"),
        Topic("Augmented reality"),
        Topic("Autism spectrum disorder"),
        Topic("Bacteria"),
        Topic("Beauty"),
        Topic("Bees"),
        Topic("Behavioral economics"),
        Topic("Best of the Web"),
        Topic("Big Bang"),
        Topic("Biodiversity"),
        Topic("Bioethics"),
        Topic("Biology"),
        Topic("Biomimicry"),
        Topic("Bionics"),
        Topic("Biosphere"),
        Topic("Biotech"),
        Topic("Birds"),
        Topic("Blindness"),
        Topic("Blockchain"),
        Topic("Body language"),
        Topic("Books"),
        Topic("Botany"),
        Topic("Brain"),
        Topic("Brazil"),
        Topic("Buddhism"),
        Topic("Bullying"),
        Topic("Business"),
        Topic("Cancer"),
        Topic("Capitalism"),
        Topic("Chemistry"),
        Topic("China"),
        Topic("Christianity"),
        Topic("Cities"),
        Topic("Climate change"),
        Topic("Code"),
        Topic("Cognitive science"),
        Topic("Collaboration"),
        Topic("Collective"),
        Topic("Comedy"),
        Topic("Communication"),
        Topic("Community"),
        Topic("Compassion"),
        Topic("Computers"),
        Topic("Conducting"),
        Topic("Consciousness"),
        Topic("Conservation"),
        Topic("Consumerism"),
        Topic("Coral reefs"),
        Topic("Coronavirus"),
        Topic("Corruption"),
        Topic("Countdown"),
        Topic("Creativity"),
        Topic("Crime"),
        Topic("CRISPR"),
        Topic("Crowdsourcing"),
        Topic("Cryptocurrency"),
        Topic("Culture"),
        Topic("Curiosity"),
        Topic("Cyber security"),
        Topic("Dance"),
        Topic("Dark matter"),
        Topic("Data"),
        Topic("Death"),
        Topic("Decision-making"),
        Topic("Deextinction"),
        Topic("Demo"),
        Topic("Democracy"),
        Topic("Depression"),
        Topic("Design"),
        Topic("Dinosaurs"),
        Topic("Disability"),
        Topic("Discovery"),
        Topic("Disease"),
        Topic("Diversity"),
        Topic("DNA"),
        Topic("Driverless cars"),
        Topic("Drones"),
        Topic("Drugs"),
        Topic("Ebola"),
        Topic("Ecology"),
        Topic("Economics"),
        Topic("Education"),
        Topic("Egypt"),
        Topic("Electricity"),
        Topic("Emotions"),
        Topic("Empathy"),
        Topic("Encryption"),
        Topic("Energy"),
        Topic("Engineering"),
        Topic("Entertainment"),
        Topic("Entrepreneur"),
        Topic("Environment"),
        Topic("Equality"),
        Topic("Ethics"),
        Topic("Europe"),
        Topic("Evolution"),
        Topic("Exercise"),
        Topic("Exploration"),
        Topic("Family"),
        Topic("Farming"),
        Topic("Fashion"),
        Topic("Fear"),
        Topic("Feminism"),
        Topic("Film"),
        Topic("Finance"),
        Topic("Fish"),
        Topic("Flight"),
        Topic("Food"),
        Topic("Forensics"),
        Topic("Fossil fuels"),
        Topic("Friendship"),
        Topic("Fungi"),
        Topic("Future"),
        Topic("Gaming"),
        Topic("Gardening"),
        Topic("Gender"),
        Topic("Genetics"),
        Topic("Geology"),
        Topic("Glaciers"),
        Topic("Global issues"),
        Topic("Goals"),
        Topic("Government"),
        Topic("Grammar"),
        Topic("Graphic design"),
        Topic("Happiness"),
        Topic("Health"),
        Topic("Health care"),
        Topic("Hearing"),
        Topic("Heart"),
        Topic("Hinduism"),
        Topic("History"),
        Topic("Homelessness"),
        Topic("Human body"),
        Topic("Human rights"),
        Topic("Humanities"),
        Topic("Humanity"),
        Topic("Humor"),
        Topic("Ideas"),
        Topic("Identity"),
        Topic("Illness"),
        Topic("Illusion"),
        Topic("Immigration"),
        Topic("Inclusion"),
        Topic("India"),
        Topic("Indigenous peoples"),
        Topic("Industrial design"),
        Topic("Infrastructure"),
        Topic("Innovation"),
        Topic("Insects"),
        Topic("International development"),
        Topic("International relations"),
        Topic("Internet"),
        Topic("Interview"),
        Topic("Invention"),
        Topic("Investing"),
        Topic("Islam"),
        Topic("Journalism"),
        Topic("Judaism"),
        Topic("Justice system"),
        Topic("Kids"),
        Topic("Language"),
        Topic("Law"),
        Topic("Leadership"),
        Topic("LGBTQIA+"),
        Topic("Library"),
        Topic("Life"),
        Topic("Literature"),
        Topic("Love"),
        Topic("Machine learning"),
        Topic("Magic"),
        Topic("Manufacturing"),
        Topic("Maps"),
        Topic("Marine biology"),
        Topic("Marketing"),
        Topic("Mars"),
        Topic("Math"),
        Topic("Media"),
        Topic("Medical imaging"),
        Topic("Medical research"),
        Topic("Medicine"),
        Topic("Meditation"),
        Topic("Memory"),
        Topic("Mental health"),
        Topic("Metaverse"),
        Topic("Microbes"),
        Topic("Microbiology"),
        Topic("Middle East"),
        Topic("Military"),
        Topic("Mindfulness"),
        Topic("Mission Blue"),
        Topic("Money"),
        Topic("Moon"),
        Topic("Motivation"),
        Topic("Museums"),
        Topic("Music"),
        Topic("Nanotechnology"),
        Topic("NASA"),
        Topic("Natural disaster"),
        Topic("Natural resources"),
        Topic("Nature"),
        Topic("Neurology"),
        Topic("Neuroscience"),
        Topic("NFTs"),
        Topic("Nuclear energy"),
        Topic("Ocean"),
        Topic("Online privacy"),
        Topic("Pain"),
        Topic("Painting"),
        Topic("Paleontology"),
        Topic("Pandemic"),
        Topic("Parenting"),
        Topic("Performance"),
        Topic("Person"),
        Topic("Personal growth"),
        Topic("Personality"),
        Topic("Philanthropy"),
        Topic("Philosophy"),
        Topic("Photography"),
        Topic("Physics"),
        Topic("Planets"),
        Topic("Plants"),
        Topic("Plastic"),
        Topic("Podcast"),
        Topic("Poetry"),
        Topic("Policy"),
        Topic("Politics"),
        Topic("Pollution"),
        Topic("Potential"),
        Topic("Poverty"),
        Topic("Pregnancy"),
        Topic("Primates"),
        Topic("Prison"),
        Topic("Product design"),
        Topic("Productivity"),
        Topic("Prosthetics"),
        Topic("Protest"),
        Topic("Psychology"),
        Topic("PTSD"),
        Topic("Public health"),
        Topic("Public space"),
        Topic("Public speaking"),
        Topic("Quantum"),
        Topic("Race"),
        Topic("Refugees"),
        Topic("Relationships"),
        Topic("Religion"),
        Topic("Renewable energy"),
        Topic("Resources"),
        Topic("Rivers"),
        Topic("Robots"),
        Topic("Rocket science"),
        Topic("Science"),
        Topic("Science fiction"),
        Topic("Self"),
        Topic("Sex"),
        Topic("Sexual violence"),
        Topic("Shopping"),
        Topic("Sight"),
        Topic("Slavery"),
        Topic("Sleep"),
        Topic("Smell"),
        Topic("Social change"),
        Topic("Social media"),
        Topic("Society"),
        Topic("Sociology"),
        Topic("Software"),
        Topic("Solar energy"),
        Topic("Solar system"),
        Topic("Sound"),
        Topic("South America"),
        Topic("Space"),
        Topic("Spoken word"),
        Topic("Sports"),
        Topic("Statistics"),
        Topic("Storytelling"),
        Topic("Street art"),
        Topic("String theory"),
        Topic("Success"),
        Topic("Suicide"),
        Topic("Sun"),
        Topic("Surgery"),
        Topic("Surveillance"),
        Topic("Sustain"),
        Topic("Sustainability"),
        Topic("Synthetic biology"),
        Topic("Talks"),
        Topic("Teaching"),
        Topic("Technology"),
        Topic("TED"),
        Topic("TED Books"),
        Topic("TED Connects"),
        Topic("TED en Español"),
        Topic("TED Fellows"),
        Topic("TED Membership"),
        Topic("TED Prize"),
        Topic("TED Residency"),
        Topic("TED Talk"),
        Topic("TED Talks"),
        Topic("TED-Ed"),
        Topic("TEDMED"),
        Topic("TEDx"),
        Topic("Telescopes"),
        Topic("Television"),
        Topic("Terrorism"),
        Topic("Theater"),
        Topic("Time"),
        Topic("Toys"),
        Topic("Transgender"),
        Topic("Translation"),
        Topic("Transportation"),
        Topic("Travel"),
        Topic("Trees"),
        Topic("Trust"),
        Topic("TV"),
        Topic("Typography"),
        Topic("United States"),
        Topic("Universe"),
        Topic("Urban planning"),
        Topic("UX design"),
        Topic("Vaccines"),
        Topic("Veganism"),
        Topic("Violence"),
        Topic("Virtual reality"),
        Topic("Virus"),
        Topic("Visualizations"),
        Topic("VOD"),
        Topic("Vulnerability"),
        Topic("War"),
        Topic("Water"),
        Topic("Weather"),
        Topic("Wind energy"),
        Topic("Women"),
        Topic("Women in business"),
        Topic("Work"),
        Topic("Work-life balance"),
        Topic("Worklife"),
        Topic("Writing"),
        Topic("Youth"),
        Topic("3D printing"),
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            Pair("<Select>", ""),
            Pair("0–6 minutes", "0-6"),
            Pair("6–12 minutes", "6-12"),
            Pair("12–18 minutes", "12-18"),
            Pair("18+ minutes", "18%2B"),
        )
    )

    private class SortFilter : UriPartFilter(
        "Sort by",
        arrayOf(
            Pair("Newest", "newest"),
            Pair("Relevance", "relevance"),
            Pair("Oldest", "oldest"),
            Pair("Most Viewed", "popular"),
        )
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_ENTRIES
            setDefaultValue("English")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subLangPref)
    }

    companion object {
        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private val PREF_SUB_ENTRIES = arrayOf(
            "Afrikaans",
            "Albanian",
            "Algerian Arabic",
            "Amharic",
            "Arabic",
            "Armenian",
            "Assamese",
            "Asturian",
            "Azerbaijani",
            "Basque",
            "Belarusian",
            "Bengali",
            "Bislama",
            "Bosnian",
            "Bulgarian",
            "Burmese",
            "Catalan",
            "Cebuano",
            "Chinese, Simplified",
            "Chinese, Traditional",
            "Chinese, Yue",
            "Creole, Haitian",
            "Croatian",
            "Czech",
            "Danish",
            "Dutch",
            "Dzongkha",
            "English",
            "Esperanto",
            "Estonian",
            "Faroese",
            "Filipino",
            "Finnish",
            "French",
            "French (Canada)",
            "Galician",
            "Georgian",
            "German",
            "Greek",
            "Gujarati",
            "Hakha Chin",
            "Hausa",
            "Hebrew",
            "Hindi",
            "Hungarian",
            "Hupa",
            "Icelandic",
            "Igbo",
            "Indonesian",
            "Ingush",
            "Irish",
            "Italian",
            "Japanese",
            "Kannada",
            "Kazakh",
            "Khmer",
            "Klingon",
            "Korean",
            "Kurdish (Central)",
            "Kyrgyz",
            "Lao",
            "Latgalian",
            "Latin",
            "Latvian",
            "Lithuanian",
            "Luxembourgish",
            "Macedonian",
            "Malagasy",
            "Malay",
            "Malayalam",
            "Maltese",
            "Marathi",
            "Mauritian Creole",
            "Mongolian",
            "Montenegrin",
            "Nepali",
            "Northern Kurdish (Kurmanji)",
            "Norwegian Bokmal",
            "Norwegian Nynorsk",
            "Occitan",
            "Pashto",
            "Persian",
            "Polish",
            "Portuguese",
            "Portuguese, Brazilian",
            "Punjabi",
            "Romanian",
            "Russian",
            "Rusyn",
            "Sardinian",
            "Serbian",
            "Serbo-Croatian",
            "Silesian",
            "Sinhala",
            "Slovak",
            "Slovenian",
            "Somali",
            "Spanish",
            "Swahili",
            "Swedish",
            "Tagalog",
            "Tajik",
            "Tamil",
            "Tatar",
            "Telugu",
            "Thai",
            "Tibetan",
            "Tunisian Arabic",
            "Turkish",
            "Turkmen",
            "Ukrainian",
            "Urdu",
            "Uyghur",
            "Uzbek",
            "Vietnamese",
        )
    }
}
