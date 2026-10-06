package com.maxrave.media3.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.maxrave.common.Config
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [35], manifest = RoboConfig.NONE)
class ServiceTrackRetentionTest {
    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "PlaybackTestDouble"
                else -> answer(method.name)
            }
        } as T

    @Test fun serviceDestructionDoesNotEraseTrackOrArtworkFromSharedPlayer() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val cover = Uri.parse("content://nothing.test/album-cover")
        player.setMediaItem(MediaItem.Builder().setMediaId("retained-track").setUri("https://example.test/audio")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Retained song").setArtworkUri(cover).build()).build())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var stopCalls = 0
        val adapter = proxy<MediaPlayerInterface> { name -> when (name) {
            "getPlayWhenReady" -> true
            "getMediaItemCount" -> player.mediaItemCount
            "getPlaybackState" -> Player.STATE_READY
            else -> null
        } }
        val handler = proxy<MediaPlayerHandler> { name -> when (name) {
            "getPlayer" -> adapter
            "stopPlaybackSession" -> { stopCalls++; player.clearMediaItems(); null }
            else -> null
        } }
        startKoin { modules(module {
            single<Player>(named(Config.MAIN_PLAYER)) { player }
            single { CoilBitmapLoader(context, scope) }
            single<MediaLibrarySession.Callback> { object : MediaLibrarySession.Callback {} }
            single<MediaPlayerHandler> { handler }
            single<DataStoreManager> { proxy { flowOf(DataStoreManager.FALSE) } }
            single<LyricsCanvasRepository> { proxy { null } }
        }) }
        try {
            Robolectric.buildService(SimpleMediaService::class.java).create().destroy()
            assertEquals("Service cleanup is not a user stop", 0, stopCalls)
            assertEquals("retained-track", player.currentMediaItem?.mediaId)
            assertEquals("Retained song", player.mediaMetadata.title.toString())
            assertEquals(cover, player.mediaMetadata.artworkUri)
        } finally {
            stopKoin()
            scope.cancel()
            player.release()
        }
    }
}
