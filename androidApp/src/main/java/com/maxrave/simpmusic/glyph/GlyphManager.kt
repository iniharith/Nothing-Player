package com.maxrave.simpmusic.glyph

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object GlyphManager {
    private var controller: GlyphSongController? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _hardwareReady = MutableStateFlow(false)
    val hardwareReady: StateFlow<Boolean> = _hardwareReady

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    fun initialize(application: Application) {
        if (controller != null) return
        controller =
            GlyphSongController(application) { ready ->
                _hardwareReady.value = ready
            }
        controller?.initialize()
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) {
            controller?.update(false, false, 0L)
        }
    }

    fun updatePlayback(
        playing: Boolean,
        positionMs: Long,
    ) {
        _isPlaying.value = playing
        _positionMs.value = positionMs
        if (_enabled.value) {
            controller?.update(_enabled.value, playing, positionMs)
        }
    }

    fun release() {
        controller?.release()
        controller = null
        _enabled.value = false
        _hardwareReady.value = false
    }
}
