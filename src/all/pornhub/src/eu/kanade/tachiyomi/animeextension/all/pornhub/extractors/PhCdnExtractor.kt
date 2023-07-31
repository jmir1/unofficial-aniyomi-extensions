package eu.kanade.tachiyomi.animeextension.all.pornhub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class PhCdnExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(videoUrl: String): MutableList<Video> {
        val pattern = Regex("(?<=viewkey=)[^&]+")
        val key = pattern.find(videoUrl)?.value
        val document = client.newCall(GET("https://www.pornhub.com/embed/$key")).execute().asJsoup()
        val scriptPart = document.select("body > script:nth-child(7)").html()
        // contains the stream url broken into multiple random variables and comments
        var vars = scriptPart.subSequence(
            scriptPart.indexOf("var ra"),
            scriptPart.indexOf(";flashvars.mediaDefinitions.hls") + 1
        ).toString().split(";").toMutableList()
        var toReplace = mutableListOf<Map<String, String>>()
        // items to update
        vars.forEachIndexed { index, s ->
            if (s.contains("/*")) toReplace.add(mapOf("pos" to index.toString(), "itm" to removeComment(s)))
        }
        vars = updateList(vars, toReplace)
        vars = vars.map {
            it.replace("var ", "")
        }.toMutableList()
        var hls = String()
        var quality = String()
        // create a map of variable to value
        var data = mutableMapOf<String, String>()
        for (v in vars) {
            if (v.isEmpty()) {
                continue
            }
            val index = v.indexOf("=")
            if (v.startsWith("hls")) {
                quality = v.replace("hls", "").split("=")[0]
                hls = v.subSequence(index + 1, v.length).toString()
            } else {
                val x = v.subSequence(0, index).toString()
                val y = v.subSequence(index+1, v.length).toString().replace("\"", "").replace(" + ", "")
                data.put(x, y)
            }
        }
        // replace the variables in hls from the map created above
        var finalHls = String()
        for (part in hls.split(" + ")) {
            finalHls += data.get(part)
        }
        val streamFile = client.newCall(GET(finalHls)).execute().body!!.string().split("\n").filter {
            it.contains("index")
        }[0]
        val streamUrl = finalHls.subSequence(0, finalHls.indexOf("master")).toString() + streamFile
        val videoList = mutableListOf<Video>()
        videoList.add(Video(streamUrl, quality + 'p', streamUrl))
        return videoList
    }

    fun updateList(list: MutableList<String>, replace: MutableList<Map<String, String>>): MutableList<String> {
        for (item in replace) {
            val pos = item["pos"]!!.toInt()
            val itm = item["itm"]!!
            list[pos] = itm
        }
        return list
    }

    fun removeComment(v: String): String {
        return v.replace(Regex("""(\/\/[^\n]*|\/\*(.|[\r\n])*?\*\/)"""), "")
    }
}
