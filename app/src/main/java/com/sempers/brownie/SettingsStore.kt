package com.sempers.brownie

import android.content.Context

/**
 * Single place that reads/writes NoiseEngineSettings to SharedPreferences.
 * Used by both the Activity (UI) and the Service (so playback started from the
 * notification or a Bluetooth headset uses the saved settings too).
 */
object SettingsStore {
    private const val PREFS = "BrowniePrefs"

    fun load(context: Context): NoiseEngineSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NoiseEngineSettings(
            dispersion = prefs.getFloat("dispersion", 0.3f).toDouble(),
            smoothness = prefs.getFloat("smoothness", 0.5f).toDouble(),
            stereoWidth = prefs.getFloat("panorama", 1.0f).toDouble(),
            lpfResonance = prefs.getFloat("resonance", 0.5f).toDouble(),
            volume = prefs.getFloat("volume", 1.0f).toDouble(),
            isTwoChannels = prefs.getBoolean("2channels", true),
            autoNormalize = prefs.getBoolean("autonormalize", false),
            isAmplitudeModulation = prefs.getBoolean("am", false),
            isStereoDrift = prefs.getBoolean("sd", false),
            isDeepBass = prefs.getBoolean("deepBass", true),
            lpfCutoffFrequency = prefs.getFloat("cutoff", 600.0f).toDouble()
        )
    }

    fun save(context: Context, settings: NoiseEngineSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("dispersion", settings.dispersion.toFloat())
            .putFloat("smoothness", settings.smoothness.toFloat())
            .putFloat("panorama", settings.stereoWidth.toFloat())
            .putFloat("resonance", settings.lpfResonance.toFloat())
            .putFloat("volume", settings.volume.toFloat())
            .putBoolean("2channels", settings.isTwoChannels)
            .putBoolean("autonormalize", settings.autoNormalize)
            .putBoolean("am", settings.isAmplitudeModulation)
            .putBoolean("sd", settings.isStereoDrift)
            .putBoolean("deepBass", settings.isDeepBass)
            .putFloat("cutoff", settings.lpfCutoffFrequency.toFloat())
            .apply()
    }
}
