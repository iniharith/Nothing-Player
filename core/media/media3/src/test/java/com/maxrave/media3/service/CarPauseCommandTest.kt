package com.maxrave.media3.service

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CarPauseCommandTest {
    @Test fun pauseReachesAdapterEvenWhenSessionViewAlreadyReportsPaused() {
        val decoder = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        decoder.playWhenReady = true
        val staleView = object : ForwardingPlayer(decoder) {
            override fun getPlayWhenReady() = false
        }
        val settings = Proxy.newProxyInstance(DataStoreManager::class.java.classLoader,
            arrayOf(DataStoreManager::class.java)) { _, method, _ ->
            when (method.name) {
                "getAndroidAutoLyrics" -> MutableStateFlow(DataStoreManager.FALSE)
                "getLyricsProvider" -> MutableStateFlow(DataStoreManager.NOTHINGPLAYER)
                "getYoutubeSubtitleLanguage" -> MutableStateFlow("en")
                else -> error("Unexpected settings access: ${method.name}")
            }
        } as DataStoreManager
        val repository = Proxy.newProxyInstance(LyricsCanvasRepository::class.java.classLoader,
            arrayOf(LyricsCanvasRepository::class.java)) { _, method, _ ->
            error("Lyrics should not load while disconnected: ${method.name}")
        } as LyricsCanvasRepository
        var commands = 0
        val car = CarLyricsPlayer(staleView, settings, repository) { intent ->
            commands++
            decoder.playWhenReady = intent
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(car.playWhenReady)
            assertTrue(decoder.playWhenReady)
            car.pause()
            assertFalse("An Android Auto pause must stop the actual decoder", decoder.playWhenReady)
            assertEquals(1, commands)
            car.play()
            assertTrue(decoder.playWhenReady)
            car.setPlayWhenReady(false)
            assertFalse(decoder.playWhenReady)
            assertEquals(3, commands)
        } finally { car.close(); decoder.release() }
    }
}
