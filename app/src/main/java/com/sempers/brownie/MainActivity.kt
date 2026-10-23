package com.sempers.brownie

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.Locale
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    private var isPlaying = false
    private lateinit var bgImage: ImageView
    private lateinit var overlayImage: ImageView
    private lateinit var textDbValue: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var settings: NoiseEngineSettings

    // Background change
    private val bgImages = intArrayOf(
        R.drawable.background0,
        R.drawable.background1,
        R.drawable.background2,
        R.drawable.background3,
        R.drawable.background4,
        R.drawable.background5,
        R.drawable.background6,
        R.drawable.background7,
        R.drawable.background8,
        R.drawable.background9,
        R.drawable.background10,
        R.drawable.background11,
        R.drawable.background12,
        R.drawable.background13,
        R.drawable.background14,
        R.drawable.background15,
        R.drawable.background16,
        R.drawable.background17,
        R.drawable.background18,
        R.drawable.background19,
        R.drawable.background20
    )

    private fun showInfo(@StringRes title: Int, @StringRes text: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var currentBgIndex = 0
    private val bgChangeInterval = 30_000L

    private fun crossfade(front: ImageView, back: ImageView, frontRes: Int, backRes: Int) {
        front.setImageResource(frontRes)
        back.setImageResource(backRes)
        back.alpha = 0f
        front.alpha = 1f
        val fadeIn = ObjectAnimator.ofFloat(back, "alpha", 0f, 1f)
        val fadeOut = ObjectAnimator.ofFloat(front, "alpha", 1f, 0f)
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeIn, fadeOut)
        animatorSet.duration = 1000
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.start()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val bgRunnable = object : Runnable {
        override fun run() {
            val nextIndex = (currentBgIndex + 1) % bgImages.size
            crossfade(bgImage, overlayImage, bgImages[currentBgIndex], bgImages[nextIndex])
            currentBgIndex = nextIndex
            handler.postDelayed(this, bgChangeInterval)
        }
    }

    // Noise Service
    private fun sendToService(action: String, foreground: Boolean = false) {
        val intent = NoiseService.intent(this, action)
        if (action == NoiseService.ACTION_UPDATE) {
            intent.putExtra(NoiseService.EXTRA_SETTINGS, settings.copy())
        }
        if (foreground) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }

    private fun updateNoiseService() = sendToService(NoiseService.ACTION_UPDATE)

    private fun saveSettings() = SettingsStore.save(this, settings)

    // Receiving updates from NoiseEngine through NoiseService
    private var lastDbValue = 0.0

    private val sampleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isPlaying) {
                textDbValue.setText(R.string.status_pause_short)
            } else {
                val dbValue = intent?.getDoubleExtra("dbValue", 0.0) ?: 0.0
                lastDbValue = dbValue
                textDbValue.text = String.format(Locale.US, "%.1f dB", dbValue)
            }
        }
    }

    private val toggleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !intent.getBooleanExtra("success", false)) return
            setPlayingUi(intent.getBooleanExtra("state", false), animate = true)
        }
    }

    private fun setPlayingUi(playing: Boolean, animate: Boolean) {
        val changed = playing != isPlaying
        isPlaying = playing
        if (playing) {
            textDbValue.text = String.format(Locale.US, "%.1f dB", lastDbValue)
            btnPlayPause.setImageResource(R.drawable.pause_circle)
            btnPlayPause.contentDescription = getString(R.string.action_pause)
        } else {
            textDbValue.setText(R.string.status_pause_short)
            btnPlayPause.setImageResource(R.drawable.play_circle)
            btnPlayPause.contentDescription = getString(R.string.action_play)
        }
        if (animate && changed) animatePlayPauseButton()
    }

    private fun animatePlayPauseButton() {
        btnPlayPause.animate()
            .setDuration(100)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .withEndAction {
                btnPlayPause.animate()
                    .setDuration(100)
                    .scaleX(1f)
                    .scaleY(1f)
                    .start()
            }
            .start()
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            sendToService(NoiseService.ACTION_PAUSE)
        } else {
            updateNoiseService()
            sendToService(NoiseService.ACTION_PLAY)
        }
    }

    // Notifications request
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is mandatory when targeting Android 15+; the window background
        // is dark, so use light system-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Background image may go under the status bar; the settings list must not
        // go under the navigation bar.
        val scroll = findViewById<View>(R.id.settingsScroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        val playButton = findViewById<View>(R.id.buttonPlayPause)
        val baseTopMargin = (playButton.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(playButton) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (v.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = baseTopMargin + top / 2
            v.requestLayout()
            insets
        }

        // Request Notifications
        askNotificationPermission()

        // Main controls
        textDbValue = findViewById(R.id.textDbValue)
        btnPlayPause = findViewById(R.id.buttonPlayPause)

        // Background changing
        bgImage = findViewById(R.id.imageBackground)
        overlayImage = findViewById(R.id.imageOverlay)
        if (savedInstanceState != null) {
            currentBgIndex = savedInstanceState.getInt("bgIndex", 0) % bgImages.size
        }
        bgImage.setImageResource(bgImages[currentBgIndex])

        // Pause initially; the service reports the real state in onStart()
        setPlayingUi(false, animate = false)
        btnPlayPause.setOnClickListener { togglePlayPause() }

        // Reading settings from preferences
        settings = SettingsStore.load(this)

        // Initialization from NoiseEngineSetting
        val seekDispersion = findViewById<SeekBar>(R.id.seekDispersion)
        val textDispersion = findViewById<TextView>(R.id.textDispersionValue)
        val seekSmoothness = findViewById<SeekBar>(R.id.seekSmoothness)
        val textSmoothness = findViewById<TextView>(R.id.textSmoothnessValue)
        val seekStereoWidth = findViewById<SeekBar>(R.id.seekStereoWidth)
        val textStereoWidth = findViewById<TextView>(R.id.textStereoWidthValue)
        val seekLPF = findViewById<SeekBar>(R.id.seekLPFCoef)
        val textLPF = findViewById<TextView>(R.id.textLPFCoefValue)
        val seekVolume = findViewById<SeekBar>(R.id.seekVolume)
        val textVolume = findViewById<TextView>(R.id.textVolumeValue)
        val checkTwoChannels = findViewById<CheckBox>(R.id.checkboxIsTwoChannels)
        val checkAutoNormalize = findViewById<CheckBox>(R.id.checkboxAutoNormalize)
        val checkSD = findViewById<CheckBox>(R.id.checkboxStereoDrift)
        val checkDeepBass = findViewById<CheckBox>(R.id.checkboxDeepBass)
        val seekCutoff = findViewById<SeekBar>(R.id.seekCutoff)
        val textCutoff = findViewById<TextView>(R.id.textCutoffValue)

        fun percent(v: Int) = getString(R.string.value_percent, v)
        fun hertz(v: Int) = getString(R.string.value_hz, v)

        seekVolume.isEnabled = !settings.autoNormalize
        seekStereoWidth.isEnabled = settings.isTwoChannels

        seekDispersion.progress = (settings.dispersion * 100.0).roundToInt()
        textDispersion.text = percent((settings.dispersion * 100.0).roundToInt())
        seekSmoothness.progress = (settings.smoothness * 100.0).roundToInt()
        textSmoothness.text = percent((settings.smoothness * 100.0).roundToInt())
        seekStereoWidth.progress = (settings.stereoWidth * 100.0).roundToInt()
        textStereoWidth.text = percent((settings.stereoWidth * 100.0).roundToInt())
        seekLPF.progress = (settings.lpfResonance * 100.0).roundToInt()
        textLPF.text = percent((settings.lpfResonance * 100.0).roundToInt())
        seekVolume.progress = (settings.volume * 100.0).roundToInt()
        textVolume.text = percent((settings.volume * 100.0).roundToInt())
        checkTwoChannels.isChecked = settings.isTwoChannels
        checkAutoNormalize.isChecked = settings.autoNormalize
        checkSD.isChecked = settings.isStereoDrift
        checkDeepBass.isChecked = settings.isDeepBass
        seekCutoff.progress = settings.lpfCutoffFrequency.roundToInt()
        textCutoff.text = hertz(settings.lpfCutoffFrequency.roundToInt())

        /** Seekbar listener; [live] = push to the engine while dragging, not only on release. */
        fun bind(seek: SeekBar, label: TextView, live: Boolean, format: (Int) -> String, apply: (Int) -> Unit) {
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = format(progress)
                    apply(progress)
                    if (live && fromUser) updateNoiseService()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    updateNoiseService()
                    saveSettings()
                }
            })
        }

        bind(seekDispersion, textDispersion, false, ::percent) { settings.dispersion = it * 0.01 }
        bind(seekSmoothness, textSmoothness, false, ::percent) { settings.smoothness = it * 0.01 }
        bind(seekStereoWidth, textStereoWidth, false, ::percent) { settings.stereoWidth = it * 0.01 }
        bind(seekLPF, textLPF, false, ::percent) { settings.lpfResonance = it * 0.01 }
        bind(seekVolume, textVolume, true, ::percent) { settings.volume = it * 0.01 }
        bind(seekCutoff, textCutoff, true, ::hertz) { settings.lpfCutoffFrequency = it.toDouble() }

        checkTwoChannels.setOnCheckedChangeListener { _, isChecked ->
            settings.isTwoChannels = isChecked
            updateNoiseService()
            saveSettings()
        }

        checkAutoNormalize.setOnCheckedChangeListener { _, isChecked ->
            settings.autoNormalize = isChecked
            seekVolume.isEnabled = !isChecked
            updateNoiseService()
            saveSettings()
        }

        checkSD.setOnCheckedChangeListener { _, isChecked ->
            settings.isStereoDrift = isChecked
            updateNoiseService()
            saveSettings()
        }

        checkDeepBass.setOnCheckedChangeListener { _, isChecked ->
            settings.isDeepBass = isChecked
            updateNoiseService()
            saveSettings()
        }

        listOf(
            R.id.infoDispersion  to (R.string.label_dispersion   to R.string.info_dispersion),
            R.id.infoSmoothness  to (R.string.label_smoothness   to R.string.info_smoothness),
            R.id.infoStereoWidth to (R.string.label_stereo_width to R.string.info_stereo_width),
            R.id.infoCutoff      to (R.string.label_cutoff       to R.string.info_cutoff),
            R.id.infoResonance   to (R.string.label_resonance    to R.string.info_resonance),
            R.id.infoVolume      to (R.string.label_volume       to R.string.info_volume),
        ).forEach { (viewId, texts) ->
            findViewById<View>(viewId).setOnClickListener { showInfo(texts.first, texts.second) }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            sampleUpdateReceiver,
            IntentFilter(NoiseService.BROADCAST_SAMPLE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            toggleUpdateReceiver,
            IntentFilter(NoiseService.BROADCAST_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Brings the service up (or reconnects to a running one) and asks it to report
        // the current playing state back to us.
        sendToService(NoiseService.ACTION_START, foreground = true)
        handler.postDelayed(bgRunnable, bgChangeInterval)
    }

    override fun onStop() {
        handler.removeCallbacks(bgRunnable)
        unregisterReceiver(sampleUpdateReceiver)
        unregisterReceiver(toggleUpdateReceiver)
        saveSettings()
        // Leaving the app while paused: shut the service down so no idle
        // notification is left behind. While playing it keeps running in background.
        if (!isPlaying && !isChangingConfigurations) {
            sendToService(NoiseService.ACTION_STOP)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("bgIndex", currentBgIndex)
    }
}
