package com.buildx.ai.system

/** Picks the first video out of a YouTube search results page. Fragile by nature (YouTube changes its page). */
object YoutubeResults {
    private val id = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    fun firstVideoId(html: String): String? = id.find(html)?.groupValues?.get(1)

    fun searchUrl(query: String): String =
        "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&sp=EgIQAQ%253D%253D"
}
