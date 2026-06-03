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

    // FIX: use the genuine round-robin endpoint — de1.api... was a single hardcoded node
    private const val BASE = "https://all.api.radio-browser.info/json"

    /** Search by station name, returns up to [limit] results */
    fun searchByName(query: String, limit: Int = 30): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return fetch("$BASE/stations/search?name=$encoded&limit=$limit&order=votes&reverse=true&hidebroken=true")
    }

    /** Search by tag/genre */
    fun searchByTag(tag: String, limit: Int = 30): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(tag, "UTF-8")
        return fetch("$BASE/stations/search?tag=$encoded&limit=$limit&order=votes&reverse=true&hidebroken=true")
    }

    private fun fetch(urlStr: String): List<SearchResult> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RadioWidget/1.0")
            connectTimeout = 5000
            readTimeout    = 8000
        }
        // FIX: try-finally guarantees disconnect() even when an exception is thrown
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val arr  = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val url = obj.optString("url_resolved").ifBlank { obj.optString("url") }
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
        } finally {
            conn.disconnect()
        }
    }
}
