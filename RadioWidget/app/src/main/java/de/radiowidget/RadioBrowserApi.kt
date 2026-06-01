package de.radiowidget

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class SearchResult(
    val name: String,
    val genre: String,
    val country: String,
    val bitrate: Int,
    val streamUrl: String,
    val codec: String
)

object RadioBrowserApi {

    // radio-browser.info provides a round-robin DNS – same backend Plasma uses
    private const val BASE = "https://de1.api.radio-browser.info/json"

    /** Search by station name, returns up to [limit] results */
    fun searchByName(query: String, limit: Int = 30): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/stations/search?name=$encoded&limit=$limit&order=votes&reverse=true&hidebroken=true"
        return fetch(url)
    }

    /** Search by tag/genre */
    fun searchByTag(tag: String, limit: Int = 30): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(tag, "UTF-8")
        val url = "$BASE/stations/search?tag=$encoded&limit=$limit&order=votes&reverse=true&hidebroken=true"
        return fetch(url)
    }

    private fun fetch(urlStr: String): List<SearchResult> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RadioWidget/1.0")
            connectTimeout = 5000
            readTimeout = 8000
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val url = obj.optString("url_resolved").ifBlank {
                obj.optString("url")
            }
            if (url.isBlank()) return@mapNotNull null
            SearchResult(
                name      = obj.optString("name", "Unbekannt").trim(),
                genre     = obj.optString("tags", "").split(",").firstOrNull()?.trim() ?: "",
                country   = obj.optString("country", ""),
                bitrate   = obj.optInt("bitrate", 0),
                streamUrl = url,
                codec     = obj.optString("codec", "")
            )
        }
    }
}
