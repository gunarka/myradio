package de.radiowidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class RadioStation(
    val name: String,
    val shortName: String,
    val frequency: String,
    val genre: String,
    val streamUrl: String,
    val isCustom: Boolean = false
)

object StationRepository {

    private const val PREFS   = "radio_prefs"
    private const val KEY_ALL = "all_stations_ordered"

    private val defaults = listOf(
        RadioStation("WDR 1Live",      "1L",  "104.2 FM", "Pop / Rock",  "https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3"),
        RadioStation("WDR 2",          "W2",  "99.2 FM",  "Pop",         "https://wdr-wdr2-aachenundregion.icecastssl.wdr.de/wdr/wdr2/aachenundregion/mp3/128/stream.mp3"),
        RadioStation("WDR 3",          "W3",  "95.9 FM",  "Klassik",     "https://wdr-wdr3-live.icecastssl.wdr.de/wdr/wdr3/live/mp3/128/stream.mp3"),
        RadioStation("Deutschlandfunk","DLF", "97.7 FM",  "Info",        "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3"),
        RadioStation("SWR3",           "SWR", "93.4 FM",  "Pop",         "https://liveradio.swr.de/sw282p3/swr3/play.mp3"),
        RadioStation("Energy NRW",     "ENY", "90.5 FM",  "Dance / EDM", "https://stream.1a-webradio.de/energy-nrw/mp3-192"),
        RadioStation("Antenne 1",      "A1",  "101.3 FM", "Pop / Hits",  "https://stream.antenne1.de/a1stg/livestream2.mp3"),
        RadioStation("NDR Info",       "NDR", "DAB+",     "News",        "https://icecast.ndr.de/ndr/ndrinfo/niedersachsen/mp3/128/stream.mp3")
    )

    /** Returns the current ordered list, initializing from defaults on first run. */
    fun getAllVisible(context: Context): List<RadioStation> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_ALL, null) ?: return defaults.also { saveAll(context, it) }
        return parseList(json)
    }

    fun addCustom(context: Context, station: RadioStation) {
        val list = getAllVisible(context).toMutableList()
        list.add(station.copy(isCustom = true))
        saveAll(context, list)
    }

    fun remove(context: Context, streamUrl: String) {
        val list = getAllVisible(context).filter { it.streamUrl != streamUrl }
        saveAll(context, list)
    }

    /** Persists a reordered list as-is. */
    fun saveAll(context: Context, list: List<RadioStation>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("name",      s.name)
                put("shortName", s.shortName)
                put("frequency", s.frequency)
                put("genre",     s.genre)
                put("streamUrl", s.streamUrl)
                put("isCustom",  s.isCustom)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALL, arr.toString()).apply()
    }

    // FIX: wrapped in try-catch — corrupt SharedPreferences JSON no longer crashes the widget
    private fun parseList(json: String): List<RadioStation> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RadioStation(
                name      = o.getString("name"),
                shortName = o.getString("shortName"),
                frequency = o.optString("frequency", "Stream"),
                genre     = o.optString("genre", ""),
                streamUrl = o.getString("streamUrl"),
                isCustom  = o.optBoolean("isCustom", false)
            )
        }
    } catch (e: JSONException) {
        defaults    // FIX: graceful fallback to defaults instead of propagating the crash
    }
}
