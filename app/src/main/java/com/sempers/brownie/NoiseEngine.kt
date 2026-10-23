package com.sempers.brownie

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NoiseEngine {

    // Constants
    companion object {
        private const val SAMPLE_RATE = 44100
        private val MIN_AMP = 10.0.pow(-72 / 20.0)
        private const val MAX_AUTO_GAIN = 50.0

        // Modulations
        private const val AM_SPEED = 0.0333    // Hz
        private const val AM_DEPTH = 0.2
        private const val DRIFT_SPEED = 0.0333 // Hz
        private const val DRIFT_DEPTH = 0.1
    }

    /** Bi-quad low-pass filter; coefficients are shared by both channels. **/
    private class Lpf {
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var lastResonance = -1.0
        var lastCutoff = -1.0
        val l = FilterState()
        val r = FilterState()

        fun calculate(resonance: Double, cutoff: Double) {
            val omega = 2.0 * PI * cutoff / SAMPLE_RATE
            val sinOmega = sin(omega)
            val cosOmega = cos(omega)
            val alpha = sinOmega / (2.0 * resonance)

            val a0 = 1 + alpha
            b0 = (1 - cosOmega) / 2 / a0
            b1 = (1 - cosOmega) / a0
            b2 = (1 - cosOmega) / 2 / a0
            a1 = -2 * cosOmega / a0
            a2 = (1 - alpha) / a0

            lastResonance = resonance
            lastCutoff = cutoff
        }

        fun apply(input: Double, state: FilterState): Double {
            val output = b0 * input + b1 * state.x1 + b2 * state.x2 - a1 * state.y1 - a2 * state.y2
            state.x2 = state.x1
            state.x1 = input
            state.y2 = state.y1
            state.y1 = output
            return output
        }
    }

    /** One playback run: its own AudioTrack and worker thread. **/
    private class Session(val track: AudioTrack) {
        @Volatile var running = true
    }

    @Volatile
    private var session: Session? = null

    val isPlaying: Boolean get() = session != null

    // Settings for the foreground service's noise engine
    @Volatile
    private var settingsVolatile = NoiseEngineSettings()

    // Handler of updated data from the service: (linear, rms, dB, autoGain).
    // Called on the audio thread.
    var onSampleUpdate: ((a: Double, b: Double, c: Double, d: Double) -> Unit)? = null

    fun updateSettings(newSettings: NoiseEngineSettings) {
        settingsVolatile = newSettings
    }


    /** @return true if playback is running after the call. */
    fun start(): Boolean {
        if (session != null) return true

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val bufferSize = minBuffer * 8

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return false
        }

        val s = Session(track)
        session = s
        thread(name = "BrownieNoise") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runSession(s, ShortArray(bufferSize))
        }
        return true
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.running = false
        // pause() interrupts a blocking write() so the worker thread exits promptly;
        // the worker itself releases the track.
        synchronized(s) {
            try {
                s.track.pause()
                s.track.flush()
            } catch (_: IllegalStateException) {
                // already released by the worker
            }
        }
    }

    // ---------------------------------------------------------------------
    // Audio thread
    // ---------------------------------------------------------------------

    private fun generate(inBrown: Double, dispersion: Double): Double {
        var white = Random.nextDouble(-dispersion, dispersion)
        if ((inBrown == 1.0 && white > 0) || (inBrown == -1.0 && white < 0)) {
            white = -white
        }
        return (inBrown + white).coerceIn(-1.0, 1.0)
    }

    private fun runSession(s: Session, buffer: ShortArray) {
        val track = s.track
        val lpf = Lpf()

        val initialSettings = settingsVolatile
        val initialDispersion = initialSettings.dispersion.coerceIn(0.01, 0.99)
        var brown = Random.nextDouble(-initialDispersion, initialDispersion)
        var brownL = Random.nextDouble(-initialDispersion, initialDispersion)
        var brownR = Random.nextDouble(-initialDispersion, initialDispersion)
        var prev = brown
        var prevL = brownL
        var prevR = brownR
        var phaseAM = 0.0
        var phaseDrift = 0.0
        var autoGain = -1.0
        var avgBufferLevel = initialSettings.normLevel

        try {
            track.play()

            while (s.running) {
                val settings = settingsVolatile
                var count = 0                                               // discrete count
                var sum = 0.0                                               // avg sum
                var sum2 = 0.0                                              // rms sum
                val dispersion = settings.dispersion.coerceIn(0.01, 0.99)
                val smoothFactor = if (settings.isDeepBass)
                    settings.smoothness.coerceIn(0.01, 0.99) * 100.0        // FIR mode
                else
                    settings.smoothness.coerceIn(0.01, 0.99)                // IIR mode
                val panDelta = settings.stereoWidth * 0.2                   // to pan in mono mode
                val resonance = (1.0 - settings.lpfResonance).coerceIn(0.01, 0.99)  // lpf resonance
                val cutoff = settings.lpfCutoffFrequency.coerceIn(20.0, SAMPLE_RATE / 2.0 - 100.0)
                if (lpf.lastResonance != resonance || lpf.lastCutoff != cutoff) {
                    lpf.calculate(resonance, cutoff)
                }
                if (settings.autoNormalize) {
                    autoGain = if (autoGain < 0) 1.0
                    else (autoGain * settings.normLevel / max(avgBufferLevel, MIN_AMP))
                        .coerceIn(0.0, MAX_AUTO_GAIN)
                } else {
                    autoGain = -1.0
                }

                for (i in buffer.indices step 2) {
                    // Generating mono
                    brown = generate(brown, dispersion)
                    val sample = if (settings.isDeepBass)
                        (prev * smoothFactor + brown) / (smoothFactor + 1)      // FIR
                    else prev * smoothFactor + brown * (1.0 - smoothFactor)     // IIR
                    var left = sample
                    var right = sample

                    // Generating two independent channels
                    if (settings.isTwoChannels) {
                        brownL = generate(brownL, dispersion)
                        brownR = generate(brownR, dispersion)
                        if (settings.isDeepBass) {
                            left = (prevL * smoothFactor + brownL) / (smoothFactor + 1)   // FIR
                            right = (prevR * smoothFactor + brownR) / (smoothFactor + 1)
                        } else {
                            left = prevL * smoothFactor + brownL * (1.0 - smoothFactor)   // IIR
                            right = prevR * smoothFactor + brownR * (1.0 - smoothFactor)
                        }
                    }

                    // Saving previous values before sound postprocessing
                    prev = sample
                    prevL = left
                    prevR = right

                    // Panning
                    if (!settings.isTwoChannels) {
                        //left += panDelta
                        //right -= panDelta
                    } else {
                        val mid = (left + right) / 2
                        left = settings.stereoWidth * left + (1 - settings.stereoWidth) * mid
                        right = settings.stereoWidth * right + (1 - settings.stereoWidth) * mid
                    }

                    // Amplitude modulation
                    if (settings.isAmplitudeModulation) {
                        val am = 1.0 + AM_DEPTH * sin(phaseAM)
                        phaseAM = (phaseAM + 2.0 * PI * AM_SPEED / SAMPLE_RATE) % (2.0 * PI)
                        left *= am
                        right *= am
                    }

                    // Stereo drift — TEMP disabled, checkbox is used for A/B of the pan formula
                    if (settings.isStereoDrift) {
                        val drift = DRIFT_DEPTH * sin(phaseDrift)
                        phaseDrift = (phaseDrift + 2.0 * PI * DRIFT_SPEED / SAMPLE_RATE) % (2.0 * PI)
                        left *= (1 - drift)
                        right *= (1 + drift)
                    }

                    // LPF, excluding the stage if is 0
                    if (settings.lpfResonance >= 0.01) {
                        left = lpf.apply(left, lpf.l)
                        right = lpf.apply(right, lpf.r)
                    }

                    // Volume and auto-normalization
                    if (settings.autoNormalize) {
                        left *= autoGain
                        right *= autoGain
                    } else {
                        left *= settings.volume
                        right *= settings.volume
                    }

                    // Limiter
                    left = left.coerceIn(-0.99999, 0.99999)
                    right = right.coerceIn(-0.99999, 0.99999)

                    // Counting two averages over Buffer after LPF and Volume
                    count++
                    sum += (abs(left) + abs(right)) / 2.0
                    sum2 += left * left + right * right

                    // Buffering
                    buffer[i] = (left * Short.MAX_VALUE).roundToInt().toShort()
                    buffer[i + 1] = (right * Short.MAX_VALUE).roundToInt().toShort()
                }

                avgBufferLevel = sum / count                                  // linear average
                val avgBufferRmsLevel = sqrt(sum2 / (2 * count))              // RMS average
                val dbValue = 20.0 * log10(max(avgBufferRmsLevel, MIN_AMP))   // to dB

                if (!s.running) break

                // Submitting new values to the GUI
                onSampleUpdate?.invoke(avgBufferLevel, avgBufferRmsLevel, dbValue, autoGain)

                // Writing output into the audiotrack (blocks; interrupted by pause())
                if (track.write(buffer, 0, buffer.size) < 0) break
            }
        } catch (_: Exception) {
            // track was stopped/released underneath us — just exit
        } finally {
            synchronized(s) {
                try { track.stop() } catch (_: IllegalStateException) {}
                track.release()
            }
        }
    }
}
