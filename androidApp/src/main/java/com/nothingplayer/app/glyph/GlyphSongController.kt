package com.nothingplayer.app.glyph

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class GlyphSongController(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private var manager: GlyphManager? = null
    private var ready = false
    private var lightsActive = false
    private val animationHandler = Handler(Looper.getMainLooper())
    private var animationRunning = false
    private var visualizer: Visualizer? = null
    private var smoothedAudioLevel = 0f
    private var averageAudioEnergy = 0.05f
    private var motionPhase = 0.0
    private var lastFrameAt = 0L
    @Volatile private var audioCaptureActive = false
    @Volatile private var latestAudioLevel = 0f
    @Volatile private var lastBeatAt = 0L
    @Volatile private var latestBeatStrength = 0f

    private val animationRunnable =
        object : Runnable {
            override fun run() {
                if (!animationRunning || !ready) return
                renderFrame()
                if (animationRunning && ready) {
                    animationHandler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

    private val callback =
        object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName?) {
                val glyphManager = manager ?: return
                try {
                    if (!Common.is24111()) {
                        onReadyChanged(false)
                        return
                    }
                    if (!glyphManager.register(Glyph.DEVICE_24111)) {
                        onReadyChanged(false)
                        return
                    }
                    glyphManager.openSession()
                    ready = true
                    onReadyChanged(true)
                } catch (error: Throwable) {
                    Log.w(TAG, "Glyph registration failed", error)
                    ready = false
                    onReadyChanged(false)
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                animationRunning = false
                animationHandler.removeCallbacks(animationRunnable)
                stopAudioCapture()
                ready = false
                lightsActive = false
                onReadyChanged(false)
            }
        }

    fun initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            manager =
                GlyphManager.getInstance(appContext).also { it.init(callback) }
        } catch (error: Throwable) {
            Log.w(TAG, "Glyph service unavailable", error)
            manager = null
        }
    }

    fun update(
        enabled: Boolean,
        playing: Boolean,
        playbackPositionMs: Long,
    ) {
        val glyphManager = manager ?: return
        if (!ready || !enabled || !playing) {
            stopAnimation(glyphManager)
            return
        }

        if (!animationRunning) {
            motionPhase =
                (playbackPositionMs.coerceAtLeast(0L) % SWEEP_DURATION_MS).toDouble() /
                    SWEEP_DURATION_MS * TWO_PI
            lastFrameAt = SystemClock.elapsedRealtime()
            startAudioCapture()
            animationRunning = true
            animationHandler.post(animationRunnable)
        }
    }

    private fun renderFrame() {
        val glyphManager = manager ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsedSeconds = ((now - lastFrameAt).coerceAtMost(200L)) / 1000.0
        lastFrameAt = now

        val targetLevel = if (audioCaptureActive) latestAudioLevel else FALLBACK_AUDIO_LEVEL
        val smoothing = if (targetLevel > smoothedAudioLevel) ATTACK_SMOOTHING else RELEASE_SMOOTHING
        smoothedAudioLevel += (targetLevel - smoothedAudioLevel) * smoothing
        val beatPulse =
            if (lastBeatAt == 0L) 0f
            else {
                (1f - (now - lastBeatAt).toFloat() / BEAT_DECAY_MS).coerceIn(0f, 1f) * latestBeatStrength
            }
        val rhythmLevel = (smoothedAudioLevel * 0.78f + beatPulse * 0.62f).coerceIn(0f, 1f)
        motionPhase = (motionPhase + elapsedSeconds * TWO_PI * (0.38 + rhythmLevel * 0.9)) % TWO_PI

        try {
            val builder = GlyphFrame.Builder(Glyph.DEVICE_24111)
            for (channel in 0 until CHANNEL_COUNT) {
                val position: Double
                val direction: Double
                val zoneOffset: Double
                when (channel) {
                    in 0..19 -> {
                        position = channel / 19.0
                        direction = 1.0
                        zoneOffset = 0.0
                    }
                    in 20..30 -> {
                        position = (channel - 20) / 10.0
                        direction = -1.0
                        zoneOffset = TWO_PI / 3.0
                    }
                    else -> {
                        position = (channel - 31) / 4.0
                        direction = 1.0
                        zoneOffset = TWO_PI * 2.0 / 3.0
                    }
                }
                val wave = 0.5 + 0.5 * sin(motionPhase * direction - position * TWO_PI + zoneOffset)
                val spatialLevel = 0.18 + wave * wave * 0.82
                val brightness = (0.04 + rhythmLevel * 0.96) * spatialLevel
                val intensity = (MIN_INTENSITY + brightness * (MAX_INTENSITY - MIN_INTENSITY)).roundToInt()
                builder.buildChannel(channel, intensity)
            }
            glyphManager.toggle(builder.build())
            lightsActive = true
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to animate Glyphs", error)
            animationRunning = false
            ready = false
            lightsActive = false
            onReadyChanged(false)
        }
    }

    private fun startAudioCapture() {
        if (visualizer != null || appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        var capture: Visualizer? = null
        try {
            capture =
                Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                visualizer: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int,
                            ) = Unit

                            override fun onFftDataCapture(
                                visualizer: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int,
                            ) {
                                if (fft == null || fft.size < 8) return
                                val upperBin = minOf(14, fft.size / 2)
                                var energy = 0.0
                                for (bin in 1 until upperBin) {
                                    val real = fft[bin * 2].toInt().toDouble()
                                    val imaginary = fft[bin * 2 + 1].toInt().toDouble()
                                    energy += hypot(real, imaginary)
                                }
                                val rawEnergy = (energy / (upperBin - 1) / 72.0).toFloat().coerceIn(0f, 1f)
                                latestAudioLevel = (rawEnergy * 1.8f).coerceIn(0f, 1f)

                                val now = SystemClock.elapsedRealtime()
                                val beatThreshold = max(MIN_BEAT_ENERGY, averageAudioEnergy * 1.38f)
                                if (rawEnergy > beatThreshold && now - lastBeatAt >= MIN_BEAT_INTERVAL_MS) {
                                    latestBeatStrength = (rawEnergy / (beatThreshold * 1.45f)).coerceIn(0.45f, 1f)
                                    lastBeatAt = now
                                }
                                averageAudioEnergy += (rawEnergy - averageAudioEnergy) * ENERGY_AVERAGE_SMOOTHING
                            }
                        },
                        Visualizer.getMaxCaptureRate(),
                        false,
                        true,
                    )
                    enabled = true
                }
            visualizer = capture
            audioCaptureActive = true
        } catch (error: Throwable) {
            Log.w(TAG, "Audio spectrum capture unavailable", error)
            try {
                capture?.release()
            } catch (_: Throwable) {
            }
            visualizer = null
            audioCaptureActive = false
        }
    }

    private fun stopAudioCapture() {
        val capture = visualizer
        visualizer = null
        audioCaptureActive = false
        latestAudioLevel = 0f
        lastBeatAt = 0L
        try {
            capture?.enabled = false
            capture?.release()
        } catch (_: Throwable) {
        }
    }

    private fun stopAnimation(glyphManager: GlyphManager) {
        animationRunning = false
        animationHandler.removeCallbacks(animationRunnable)
        stopAudioCapture()
        if (!lightsActive) return
        try {
            glyphManager.turnOff()
        } catch (_: Throwable) {
        }
        lightsActive = false
    }

    fun release() {
        val glyphManager = manager ?: return
        animationRunning = false
        animationHandler.removeCallbacks(animationRunnable)
        stopAudioCapture()
        try {
            glyphManager.turnOff()
            glyphManager.closeSession()
        } catch (_: GlyphException) {
        } catch (_: Throwable) {
        }
        try {
            glyphManager.unInit()
        } catch (_: Throwable) {
        }
        manager = null
        ready = false
        lightsActive = false
    }

    companion object {
        private const val TAG = "GlyphSongController"
        private const val CHANNEL_COUNT = 36
        private const val MIN_INTENSITY = 80
        private const val MAX_INTENSITY = 4000
        private const val FRAME_INTERVAL_MS = 33L
        private const val SWEEP_DURATION_MS = 1800L
        private const val FALLBACK_AUDIO_LEVEL = 0.22f
        private const val ATTACK_SMOOTHING = 0.48f
        private const val RELEASE_SMOOTHING = 0.16f
        private const val ENERGY_AVERAGE_SMOOTHING = 0.08f
        private const val MIN_BEAT_ENERGY = 0.06f
        private const val MIN_BEAT_INTERVAL_MS = 180L
        private const val BEAT_DECAY_MS = 320f
        private const val TWO_PI = Math.PI * 2.0
    }
}
