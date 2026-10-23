package com.sempers.brownie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat

class NoiseService : Service() {

    companion object {
        const val ACTION_START = "com.sempers.brownie.action.START"   // bring service up, report state
        const val ACTION_PLAY = "com.sempers.brownie.action.PLAY"
        const val ACTION_PAUSE = "com.sempers.brownie.action.PAUSE"
        const val ACTION_TOGGLE = "com.sempers.brownie.action.TOGGLE"
        const val ACTION_STOP = "com.sempers.brownie.action.STOP"
        const val ACTION_UPDATE = "com.sempers.brownie.action.UPDATE"
        const val EXTRA_SETTINGS = "settings"

        const val BROADCAST_SAMPLE = "com.sempers.brownie.SAMPLE_UPDATE"
        const val BROADCAST_STATE = "com.sempers.brownie.TOGGLE_UPDATE"

        private const val CHANNEL_ID = "Brownie"
        private const val NOTIFICATION_ID = 1
        private const val CUSTOM_ACTION_CLOSE = "close"

        fun intent(context: Context, action: String): Intent =
            Intent(context, NoiseService::class.java).setAction(action)
    }

    private val engine = NoiseEngine()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        engine.updateSettings(SettingsStore.load(this))

        engine.onSampleUpdate = { linearValue, rmsValue, dbValue, autoGain ->
            sendBroadcast(Intent(BROADCAST_SAMPLE).apply {
                `package` = packageName
                putExtra("linearValue", linearValue)
                putExtra("rmsValue", rmsValue)
                putExtra("dbValue", dbValue)
                putExtra("autoGain", autoGain)
            })
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter foreground first: the service may have been started with
        // startForegroundService(), which must be followed by startForeground().
        if (!goForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_START -> broadcastState(true)
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (engine.isPlaying) pause() else play()
            ACTION_STOP -> stopEverything()
            ACTION_UPDATE -> {
                val newSettings = intent?.let {
                    IntentCompat.getSerializableExtra(it, EXTRA_SETTINGS, NoiseEngineSettings::class.java)
                }
                if (newSettings != null) engine.updateSettings(newSettings)
            }
        }

        // Noise state can't be meaningfully restored after the process is killed.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from recents: keep playing if the user is listening,
        // otherwise there is no reason to keep the service around.
        if (!engine.isPlaying) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engine.stop()
        abandonAudioFocus()
        unregisterNoisy()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Playback control
    // ---------------------------------------------------------------------

    private fun play() {
        if (engine.isPlaying) {
            broadcastState(true)
            return
        }
        if (!requestAudioFocus()) {
            broadcastState(true)
            return
        }
        if (!engine.start()) {
            abandonAudioFocus()
            broadcastState(true)
            return
        }
        resumeOnFocusGain = false
        registerNoisy()
        onStateChanged()
    }

    private fun pause(keepFocus: Boolean = false) {
        if (!engine.isPlaying) {
            broadcastState(true)
            return
        }
        engine.stop()
        if (!keepFocus) abandonAudioFocus()
        unregisterNoisy()
        onStateChanged()
    }

    private fun stopEverything() {
        resumeOnFocusGain = false
        engine.stop()
        abandonAudioFocus()
        unregisterNoisy()
        updatePlaybackState()
        broadcastState(true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onStateChanged() {
        updatePlaybackState()
        NotificationManagerCompatSafe.notify(this, NOTIFICATION_ID, buildNotification())
        broadcastState(true)
    }

    private fun broadcastState(success: Boolean) {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            `package` = packageName
            putExtra("success", success)
            putExtra("state", engine.isPlaying)
        })
    }

    // ---------------------------------------------------------------------
    // Audio focus & "becoming noisy" (headphones unplugged)
    // ---------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // e.g. phone call: pause, resume afterwards
                if (engine.isPlaying) {
                    resumeOnFocusGain = true
                    pause(keepFocus = true)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks the volume automatically
        }
    }

    private val focusRequest: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private fun requestAudioFocus(): Boolean =
        AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnFocusGain = false
                pause()
            }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        unregisterReceiver(noisyReceiver)
        noisyRegistered = false
    }

    // ---------------------------------------------------------------------
    // Media session (Bluetooth / headset buttons, lock screen, media controls)
    // ---------------------------------------------------------------------

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "BrownieSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopEverything()
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action == CUSTOM_ACTION_CLOSE) stopEverything()
                }
            })
            setSessionActivity(openAppIntent())
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.media_title))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .build()
            )
            isActive = true
        }
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val playing = engine.isPlaying
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_ACTION_CLOSE,
                        getString(R.string.action_stop),
                        R.drawable.ic_close
                    ).build()
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun goForeground(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0
            )
            true
        } catch (e: IllegalStateException) {
            // Android 12+: ForegroundServiceStartNotAllowedException when we're not allowed
            // to enter foreground from the background. Nothing to play then — shut down.
            engine.stop()
            stopSelf()
            false
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val i = intent(this, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(this, requestCode, i, flags)
        else
            PendingIntent.getService(this, requestCode, i, flags)
    }

    private fun buildNotification(): Notification {
        val playing = engine.isPlaying
        val toggle = NotificationCompat.Action(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.action_pause else R.string.action_play),
            servicePendingIntent(ACTION_TOGGLE, 1)
        )
        val close = NotificationCompat.Action(
            R.drawable.ic_close,
            getString(R.string.action_stop),
            servicePendingIntent(ACTION_STOP, 2)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brownie)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (playing) R.string.status_playing else R.string.status_paused))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 3))
            .addAction(toggle)
            .addAction(close)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }
}

/** notify() that tolerates a missing POST_NOTIFICATIONS grant (Android 13+). */
private object NotificationManagerCompatSafe {
    fun notify(context: Context, id: Int, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}
