package de.radiowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class RadioWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY      = "de.radiowidget.ACTION_PLAY"
        const val ACTION_STOP      = "de.radiowidget.ACTION_STOP"
        const val ACTION_NEXT      = "de.radiowidget.ACTION_NEXT"
        const val ACTION_PREV      = "de.radiowidget.ACTION_PREV"
        const val ACTION_PAGE_NEXT = "de.radiowidget.ACTION_PAGE_NEXT"
        const val ACTION_PAGE_PREV = "de.radiowidget.ACTION_PAGE_PREV"
        const val EXTRA_STATION    = "station_index"
        const val PAGE_SIZE        = 5

        val SLOT_IDS = intArrayOf(
            R.id.btn_s0, R.id.btn_s1, R.id.btn_s2, R.id.btn_s3, R.id.btn_s4
        )

        // FIX: logic extracted to companion — no throwaway RadioWidgetProvider() instance needed
        fun updateAllWidgets(context: Context) {
            val mgr  = AppWidgetManager.getInstance(context)
            val comp = ComponentName(context, RadioWidgetProvider::class.java)
            mgr.getAppWidgetIds(comp).forEach { updateWidget(context, mgr, it) }
        }

        // FIX: shared helper — was duplicated in onReceive() and updateWidget()
        fun maxPage(stationCount: Int): Int =
            if (stationCount == 0) 0 else (stationCount - 1) / PAGE_SIZE

        // FIX: moved to companion — now reusable by RadioService.buildNotification()
        fun broadcast(context: Context, action: String, stationIdx: Int, reqCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, reqCode,
                Intent(context, RadioWidgetProvider::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_STATION, stationIdx)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs     = context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
            val isPlaying = prefs.getBoolean("is_playing", false)
            val stations  = StationRepository.getAllVisible(context)

            // FIX: guard against empty list — was crashing with IndexOutOfBoundsException
            if (stations.isEmpty()) {
                val views = RemoteViews(context.packageName, R.layout.widget_radio)
                views.setTextViewText(R.id.tv_station_name, "Keine Sender")
                views.setTextViewText(R.id.tv_station_genre, "")
                SLOT_IDS.forEach { views.setViewVisibility(it, View.INVISIBLE) }
                views.setViewVisibility(R.id.btn_page_prev, View.INVISIBLE)
                views.setViewVisibility(R.id.btn_page_next, View.INVISIBLE)
                views.setOnClickPendingIntent(R.id.btn_manage,
                    PendingIntent.getActivity(context, 999,
                        Intent(context, StationManagerActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                mgr.updateAppWidget(widgetId, views)
                return
            }

            val currentIdx = prefs.getInt("current_station", 0).coerceIn(0, stations.size - 1)
            val station    = stations[currentIdx]

            // FIX: uses shared maxPage() — no more inline duplication
            val maxPage = maxPage(stations.size)
            val page    = prefs.getInt("page", currentIdx / PAGE_SIZE).coerceIn(0, maxPage)
            prefs.edit().putInt("page", page).apply()

            val views = RemoteViews(context.packageName, R.layout.widget_radio)

            views.setTextViewText(R.id.tv_station_name,  station.name)
            views.setTextViewText(R.id.tv_station_genre, "${station.genre}  ·  ${station.frequency}")

            views.setImageViewResource(R.id.btn_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play)
            views.setOnClickPendingIntent(R.id.btn_play_pause,
                if (isPlaying) broadcast(context, ACTION_STOP, currentIdx, 100)
                else           broadcast(context, ACTION_PLAY, currentIdx, 101))
            views.setOnClickPendingIntent(R.id.btn_next, broadcast(context, ACTION_NEXT, currentIdx, 102))
            views.setOnClickPendingIntent(R.id.btn_prev, broadcast(context, ACTION_PREV, currentIdx, 103))

            views.setOnClickPendingIntent(R.id.btn_manage,
                PendingIntent.getActivity(context, 999,
                    Intent(context, StationManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            views.setViewVisibility(R.id.btn_page_prev, if (page > 0) View.VISIBLE else View.INVISIBLE)
            views.setViewVisibility(R.id.btn_page_next, if (page < maxPage) View.VISIBLE else View.INVISIBLE)
            views.setOnClickPendingIntent(R.id.btn_page_prev, broadcast(context, ACTION_PAGE_PREV, 0, 200))
            views.setOnClickPendingIntent(R.id.btn_page_next, broadcast(context, ACTION_PAGE_NEXT, 0, 201))

            val offset = page * PAGE_SIZE
            SLOT_IDS.forEachIndexed { i, viewId ->
                val idx = offset + i
                if (idx < stations.size) {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, stations[idx].shortName)
                    views.setOnClickPendingIntent(viewId, broadcast(context, ACTION_PLAY, idx, 300 + idx))
                    views.setInt(viewId, "setBackgroundResource",
                        if (idx == currentIdx && isPlaying) R.drawable.btn_station_active
                        else R.drawable.btn_station_normal)
                } else {
                    views.setViewVisibility(viewId, View.INVISIBLE)
                }
            }

            mgr.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs    = context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
        val stations = StationRepository.getAllVisible(context)

        when (intent.action) {
            ACTION_PLAY -> {
                val idx = intent.getIntExtra(EXTRA_STATION, 0)
                context.startForegroundService(Intent(context, RadioService::class.java).apply {
                    action = ACTION_PLAY; putExtra(EXTRA_STATION, idx)
                })
            }
            ACTION_STOP -> context.startService(
                Intent(context, RadioService::class.java).apply { action = ACTION_STOP }
            )
            ACTION_NEXT -> context.startForegroundService(
                Intent(context, RadioService::class.java).apply { action = ACTION_NEXT }
            )
            ACTION_PREV -> context.startForegroundService(
                Intent(context, RadioService::class.java).apply { action = ACTION_PREV }
            )
            ACTION_PAGE_NEXT -> {
                // FIX: guard against empty list; uses shared maxPage()
                if (stations.isNotEmpty()) {
                    val cur = prefs.getInt("page", 0)
                    prefs.edit().putInt("page", (cur + 1).coerceAtMost(maxPage(stations.size))).apply()
                }
            }
            ACTION_PAGE_PREV -> {
                val cur = prefs.getInt("page", 0)
                prefs.edit().putInt("page", (cur - 1).coerceAtLeast(0)).apply()
            }
        }
        updateAllWidgets(context)
    }
}
