package de.radiowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.app.Service
import androidx.core.app.NotificationCompat

class RadioService : Service() {

    companion object {
        const val CHANNEL_ID      = "radio_channel"
        const val NOTIFICATION_ID = 1
        const val RETRY_DELAY_MS  = 5_000L
        const val MAX_RETRIES     = 10
        // Watchdog: check every 30s if stream is still alive
        const val WATCHDOG_INTERVAL_MS = 30_000L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentStationIdx = 0
    private var isPlaying = false
    private var retryCount = 0

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    private val handler = Handler(Looper.getMainLooper())

    // Retry runnable
    private val retryRunnable = Runnable {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            val stations = StationRepository.getAllVisible(this)
            startStream(stations[currentStationIdx.coerceIn(0, stations.size - 1)])
        } else {
            isPlaying = false
            saveState(currentStationIdx, playing = false)
            RadioWidgetProvider.updateAllWidgets(this)
            releaseLocks()
        }
    }

    // Watchdog: Samsung can freeze MediaPlayer without firing onError/onCompletion
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (isPlaying) {
                val alive = try { mp != null && mp.isPlaying } catch (e: Exception) { false }
                if (!alive && retryCount == 0) {
                    // Stream silently died — restart immediately
                    retryCount = 1
                    val stations = StationRepository.getAllVisible(this@RadioService)
                    startStream(stations[currentStationIdx.coerceIn(0, stations.size - 1)])
                }
            }
            if (isPlaying) handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // SCREEN_ON receiver: resume immediately when screen turns on
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && isPlaying) {
                val mp = mediaPlayer
                val alive = try { mp != null && mp.isPlaying } catch (e: Exception) { false }
                if (!alive) {
                    retryCount = 0
                    val stations = StationRepository.getAllVisible(context)
                    startStream(stations[currentStationIdx.coerceIn(0, stations.size - 1)])
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RadioWidget::StreamingWakeLock"
        )

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "RadioWidget::StreamingWifiLock"
        )

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS            -> stopRadio()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT  -> mediaPlayer?.pause()
                    AudioManager.AUDIOFOCUS_GAIN            -> mediaPlayer?.start()
                }
            }
            .build()

        // Register SCREEN_ON receiver
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stations = StationRepository.getAllVisible(this)
        when (intent?.action) {
            RadioWidgetProvider.ACTION_PLAY -> {
                val idx = intent.getIntExtra(RadioWidgetProvider.EXTRA_STATION, currentStationIdx)
                playStation(idx)
            }
            RadioWidgetProvider.ACTION_STOP -> { stopRadio(); stopSelf() }
            RadioWidgetProvider.ACTION_NEXT -> playStation((currentStationIdx + 1) % stations.size)
            RadioWidgetProvider.ACTION_PREV -> playStation((currentStationIdx - 1 + stations.size) % stations.size)
            // Called when service is restarted by system after being killed
            null -> {
                val prefs = getSharedPreferences("radio_prefs", MODE_PRIVATE)
                if (prefs.getBoolean("is_playing", false)) {
                    playStation(prefs.getInt("current_station", 0))
                }
            }
        }
        return START_STICKY
    }

    private fun playStation(idx: Int) {
        retryCount = 0
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdogRunnable)

        currentStationIdx = idx
        val stations = StationRepository.getAllVisible(this)
        val station  = stations[idx.coerceIn(0, stations.size - 1)]

        releasePlayer()
        saveState(idx, playing = true)
        requestBatteryExemptionOnce()

        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            saveState(idx, playing = false); return
        }

        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()

        startForeground(NOTIFICATION_ID, buildNotification(station))
        startStream(station)
        RadioWidgetProvider.updateAllWidgets(this)
    }

    private fun startStream(station: RadioStation) {
        handler.removeCallbacks(retryRunnable)
        releasePlayer()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(station.streamUrl)

            setOnPreparedListener { mp ->
                mp.start()
                this@RadioService.isPlaying = true
                retryCount = 0
                saveState(currentStationIdx, playing = true)
                RadioWidgetProvider.updateAllWidgets(this@RadioService)
                // Start watchdog after stream is confirmed running
                handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
            }

            setOnErrorListener { _, _, _ ->
                handler.removeCallbacks(watchdogRunnable)
                if (retryCount < MAX_RETRIES) {
                    handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                } else {
                    this@RadioService.isPlaying = false
                    saveState(currentStationIdx, playing = false)
                    RadioWidgetProvider.updateAllWidgets(this@RadioService)
                    releaseLocks()
                }
                true
            }

            setOnCompletionListener {
                handler.removeCallbacks(watchdogRunnable)
                if (retryCount < MAX_RETRIES) {
                    handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                } else {
                    this@RadioService.isPlaying = false
                    saveState(currentStationIdx, playing = false)
                    RadioWidgetProvider.updateAllWidgets(this@RadioService)
                    releaseLocks()
                }
            }

            prepareAsync()
        }
    }

    private fun stopRadio() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdogRunnable)
        releasePlayer()
        isPlaying = false
        saveState(currentStationIdx, playing = false)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RadioWidgetProvider.updateAllWidgets(this)
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            runCatching { if (isPlaying) stop() }
            reset()
            release()
        }
        mediaPlayer = null
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    private fun saveState(idx: Int, playing: Boolean) {
        getSharedPreferences("radio_prefs", MODE_PRIVATE).edit().apply {
            putInt("current_station", idx)
            putBoolean("is_playing", playing)
            apply()
        }
    }

    private fun buildNotification(station: RadioStation): Notification {
        fun broadcast(action: String, req: Int) = PendingIntent.getBroadcast(
            this, req,
            Intent(this, RadioWidgetProvider::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(station.name)
            .setContentText("${station.genre} · ${station.frequency}")
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Zurück",  broadcast(RadioWidgetProvider.ACTION_PREV, 2))
            .addAction(android.R.drawable.ic_media_pause,    "Stop",    broadcast(RadioWidgetProvider.ACTION_STOP, 0))
            .addAction(android.R.drawable.ic_media_next,     "Weiter",  broadcast(RadioWidgetProvider.ACTION_NEXT, 1))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Radio Widget", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Streaming Radio Wiedergabe"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestBatteryExemptionOnce() {
        val prefs = getSharedPreferences("radio_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        startActivity(Intent(this, BatteryOptimizationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdogRunnable)
        unregisterReceiver(screenOnReceiver)
        stopRadio()
        super.onDestroy()
    }
}
