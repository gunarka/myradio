package de.radiowidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.app.Service
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class RadioService : Service() {

    companion object {
        const val CHANNEL_ID           = "radio_channel"
        const val NOTIFICATION_ID      = 1
        const val RETRY_DELAY_MS       = 5_000L
        const val MAX_RETRIES          = 10
        const val WATCHDOG_INTERVAL_MS = 30_000L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentStationIdx = 0
    private var isPlaying = false
    private var retryCount = 0
    // tracks intentional pause from audio focus loss — prevents watchdog false-restart
    private var isPausedForFocus = false
    // tracks explicit user pause (lock screen / notification) — service stays alive, stream pauses
    private var isUserPaused = false

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private lateinit var mediaSession: MediaSessionCompat

    private val handler = Handler(Looper.getMainLooper())

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

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (isPlaying) {
                val alive = try { mp != null && mp.isPlaying } catch (e: Exception) { false }
                // isPausedForFocus / isUserPaused guard — watchdog never restarts an intentional pause
                if (!alive && retryCount == 0 && !isPausedForFocus && !isUserPaused) {
                    retryCount = 1
                    val stations = StationRepository.getAllVisible(this@RadioService)
                    startStream(stations[currentStationIdx.coerceIn(0, stations.size - 1)])
                }
            }
            if (isPlaying) handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // also check isUserPaused to avoid restarting an intentionally paused stream
            if (intent.action == Intent.ACTION_SCREEN_ON && isPlaying && !isPausedForFocus && !isUserPaused) {
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
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
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
                    AudioManager.AUDIOFOCUS_LOSS -> stopRadio()

                    // set flag so watchdog and screenOnReceiver don't restart the paused stream
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        mediaPlayer?.pause()
                        isPausedForFocus = true
                        saveState(currentStationIdx, playing = false)
                        updateMediaSession()
                        RadioWidgetProvider.updateAllWidgets(this)
                    }

                    // FIX: only resume if we paused for focus AND we're not in a phone call.
                    // When the ringtone stops (call answered) AUDIOFOCUS_GAIN fires while the
                    // call is still active — audioManager.mode will be MODE_IN_CALL at that
                    // point, so we leave isPausedForFocus=true and stay silent.  When the call
                    // actually ends, telephony releases focus and fires AUDIOFOCUS_GAIN again;
                    // by then mode is back to MODE_NORMAL and we resume correctly.
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (isPausedForFocus && !isInCall()) {
                            mediaPlayer?.start()
                            isPausedForFocus = false
                            saveState(currentStationIdx, playing = true)
                            updateMediaSession()
                            RadioWidgetProvider.updateAllWidgets(this)
                        }
                        // if isInCall(): isPausedForFocus stays true — watchdog silent, stream stays paused
                    }
                }
            }
            .build()

        // FIX: pass RECEIVER_NOT_EXPORTED on API 33+ — avoids SecurityException on API 34 targets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                screenOnReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        }

        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stations = StationRepository.getAllVisible(this)
        when (intent?.action) {
            RadioWidgetProvider.ACTION_PLAY -> {
                val idx = intent.getIntExtra(RadioWidgetProvider.EXTRA_STATION, currentStationIdx)
                if (isUserPaused && idx == currentStationIdx && mediaPlayer != null) {
                    // Resume the already-buffered stream rather than reconnecting
                    mediaPlayer?.start()
                    isUserPaused = false
                    saveState(currentStationIdx, playing = true)
                    val station = stations.getOrNull(currentStationIdx)
                    if (station != null) startForeground(NOTIFICATION_ID, buildNotification(station, true))
                    updateMediaSession()
                    RadioWidgetProvider.updateAllWidgets(this)
                } else {
                    isUserPaused = false
                    playStation(idx)
                }
            }
            // Pause without killing the service — stream can be resumed cheaply
            RadioWidgetProvider.ACTION_PAUSE -> {
                if (isPlaying && !isUserPaused) {
                    mediaPlayer?.pause()
                    isUserPaused = true
                    saveState(currentStationIdx, playing = false)
                    val station = stations.getOrNull(currentStationIdx)
                    if (station != null) startForeground(NOTIFICATION_ID, buildNotification(station, false))
                    updateMediaSession()
                    RadioWidgetProvider.updateAllWidgets(this)
                }
            }
            RadioWidgetProvider.ACTION_STOP -> { stopRadio(); stopSelf() }
            RadioWidgetProvider.ACTION_NEXT -> playStation((currentStationIdx + 1) % stations.size)
            RadioWidgetProvider.ACTION_PREV -> playStation((currentStationIdx - 1 + stations.size) % stations.size)
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
        isPausedForFocus = false
        isUserPaused = false
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

        startForeground(NOTIFICATION_ID, buildNotification(station, true))
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
                updateMediaSession()
                RadioWidgetProvider.updateAllWidgets(this@RadioService)
                handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
            }

            // FIX: both error and completion now delegate to shared handleStreamEnd()
            setOnErrorListener { _, _, _ -> handleStreamEnd(); true }
            setOnCompletionListener { handleStreamEnd() }

            prepareAsync()
        }
    }

    // FIX: extracted from duplicate blocks in setOnErrorListener and setOnCompletionListener
    private fun handleStreamEnd() {
        handler.removeCallbacks(watchdogRunnable)
        if (retryCount < MAX_RETRIES) {
            handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        } else {
            isPlaying = false
            saveState(currentStationIdx, playing = false)
            RadioWidgetProvider.updateAllWidgets(this)
            releaseLocks()
        }
    }

    private fun stopRadio() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdogRunnable)
        releasePlayer()
        isPlaying = false
        isPausedForFocus = false
        isUserPaused = false
        saveState(currentStationIdx, playing = false)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (::mediaSession.isInitialized) {
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
        }
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

    private fun buildNotification(station: RadioStation, playing: Boolean): Notification {
        // Middle button toggles between Pause (while playing) and Play (while paused)
        val (middleIcon, middleLabel, middleAction) = if (playing) {
            Triple(android.R.drawable.ic_media_pause, "Pause", RadioWidgetProvider.ACTION_PAUSE)
        } else {
            Triple(android.R.drawable.ic_media_play,  "Play",  RadioWidgetProvider.ACTION_PLAY)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(station.name)
            .setContentText("${station.genre} · ${station.frequency}")
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Zurück",
                RadioWidgetProvider.broadcast(this, RadioWidgetProvider.ACTION_PREV, 0, 2))
            .addAction(middleIcon, middleLabel,
                RadioWidgetProvider.broadcast(this, middleAction, currentStationIdx, 0))
            .addAction(android.R.drawable.ic_media_next, "Weiter",
                RadioWidgetProvider.broadcast(this, RadioWidgetProvider.ACTION_NEXT, 0, 1))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession.sessionToken))   // ← enables lock-screen controls
            .build()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioWidget").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // Hardware/BT media buttons and lock-screen controls go through here
                override fun onPlay() {
                    if (isUserPaused && mediaPlayer != null) {
                        mediaPlayer?.start()
                        isUserPaused = false
                        saveState(currentStationIdx, playing = true)
                        val station = StationRepository.getAllVisible(this@RadioService)
                            .getOrNull(currentStationIdx)
                        if (station != null)
                            startForeground(NOTIFICATION_ID, buildNotification(station, true))
                        updateMediaSession()
                        RadioWidgetProvider.updateAllWidgets(this@RadioService)
                    } else if (isPlaying) {
                        // Already playing — no-op (e.g. double-tap from BT headset)
                    } else {
                        // Service was stopped; restart from last saved station
                        val stations = StationRepository.getAllVisible(this@RadioService)
                        if (stations.isNotEmpty()) {
                            val prefs = getSharedPreferences("radio_prefs", MODE_PRIVATE)
                            playStation(prefs.getInt("current_station", 0)
                                .coerceIn(0, stations.size - 1))
                        }
                    }
                }

                override fun onPause() {
                    if (isPlaying && !isUserPaused) {
                        mediaPlayer?.pause()
                        isUserPaused = true
                        saveState(currentStationIdx, playing = false)
                        val station = StationRepository.getAllVisible(this@RadioService)
                            .getOrNull(currentStationIdx)
                        if (station != null)
                            startForeground(NOTIFICATION_ID, buildNotification(station, false))
                        updateMediaSession()
                        RadioWidgetProvider.updateAllWidgets(this@RadioService)
                    }
                }

                override fun onStop() { stopRadio(); stopSelf() }

                override fun onSkipToNext() {
                    val stations = StationRepository.getAllVisible(this@RadioService)
                    if (stations.isNotEmpty())
                        playStation((currentStationIdx + 1) % stations.size)
                }

                override fun onSkipToPrevious() {
                    val stations = StationRepository.getAllVisible(this@RadioService)
                    if (stations.isNotEmpty())
                        playStation((currentStationIdx - 1 + stations.size) % stations.size)
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSession() {
        val station = StationRepository.getAllVisible(this).getOrNull(currentStationIdx)
        station?.let {
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  it.name)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it.genre)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,  "Radio")
                    .build()
            )
        }
        val actuallyPlaying = isPlaying && !isUserPaused && !isPausedForFocus
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (actuallyPlaying) PlaybackStateCompat.STATE_PLAYING
                    else                 PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY             or
                    PlaybackStateCompat.ACTION_PAUSE            or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE       or
                    PlaybackStateCompat.ACTION_STOP             or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT     or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
    }

    /** True when a phone call is active — used to suppress auto-resume on AUDIOFOCUS_GAIN. */
    private fun isInCall(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Radio Widget", NotificationManager.IMPORTANCE_LOW
        ).apply {
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
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        stopRadio()
        super.onDestroy()
    }
}
