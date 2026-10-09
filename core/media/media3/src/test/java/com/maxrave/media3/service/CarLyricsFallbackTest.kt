package com.maxrave.media3.service

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class CarLyricsFallbackTest {
    @Test fun failedSelectedProviderFallsBackAndDisconnectRestoresSongMetadata() {
        val decoder = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val item = MediaItem.Builder().setMediaId("song")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").setArtist("Artist").build()).build()
        val base = object : ForwardingPlayer(decoder) {
            override fun getCurrentMediaItem() = item
            override fun getMediaMetadata() = item.mediaMetadata
            override fun getDuration() = 10_000L
            override fun getCurrentPosition() = 1_000L
        }
        val provider = MutableStateFlow(DataStoreManager.NOTHINGPLAYER)
        val settings = Proxy.newProxyInstance(DataStoreManager::class.java.classLoader, arrayOf(DataStoreManager::class.java)) { _, method, _ ->
            when (method.name) {
                "getAndroidAutoLyrics" -> MutableStateFlow(DataStoreManager.TRUE)
                "getLyricsProvider" -> provider
                "getYoutubeSubtitleLanguage" -> MutableStateFlow("en")
                "getLyricsOffset" -> MutableStateFlow(0)
                "getString" -> MutableStateFlow<String?>(null)
                else -> error("Unexpected ${method.name}")
            }
        } as DataStoreManager
        var fallbackCalls = 0
        val repository = Proxy.newProxyInstance(LyricsCanvasRepository::class.java.classLoader, arrayOf(LyricsCanvasRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "getSavedLyrics" -> flowOf(null)
                "getNothingPlayerLyrics" -> flowOf(Resource.Error<Lyrics>("Offline"))
                "getLrclibLyricsData" -> {
                    fallbackCalls++
                    flowOf(Resource.Success(Lyrics(lines = listOf(Line("0", "0", words = "Current lyric")), syncType = "LINE_SYNCED")))
                }
                else -> error("Unexpected ${method.name}")
            }
        } as LyricsCanvasRepository
        val car = CarLyricsPlayer(base, settings, repository)
        try {
            car.setCarConnected(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Current lyric", car.mediaMetadata.title.toString())
            assertEquals(1, fallbackCalls)
            provider.value = DataStoreManager.LRCLIB
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, fallbackCalls)
            car.setCarConnected(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Song", car.mediaMetadata.title.toString())
        } finally { car.close(); decoder.release() }
    }
}
