package com.maxrave.media3.exoplayer

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerErrorHandoffTest {
    @Test fun emptyDelegateStaysIdleWhenControllerOpensDuringPreparation() {
        val decoder = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val stable = DelegatingForwardingPlayer(decoder)
        stable.playbackIntent = { true }
        stable.preparingNextTrack = true
        try {
            val sessionView = ForwardingSimpleBasePlayer(stable)
            assertEquals(Player.STATE_IDLE, sessionView.playbackState)
            assertTrue(sessionView.currentTimeline.isEmpty)
        } finally { decoder.release() }
    }

    @Test fun errorRemainsIdleWhenOpeningAnotherSessionViewDuringPreparation() {
        val decoder = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        decoder.setMediaItem(MediaItem.fromUri("https://example.test/failed"))
        val failure = PlaybackException("Stream failed", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val failed = object : ForwardingPlayer(decoder) {
            override fun getPlayerError() = failure
        }
        val stable = DelegatingForwardingPlayer(failed)
        stable.playbackIntent = { true }
        stable.preparingNextTrack = true
        try {
            val sessionView = ForwardingSimpleBasePlayer(stable)
            assertEquals(Player.STATE_IDLE, sessionView.playbackState)
            assertSame(failure, sessionView.playerError)
        } finally { decoder.release() }
    }

    @Test fun errorDuringHandoffNotifiesSessionAndHealthyReplacementCanBuffer() {
        val context = RuntimeEnvironment.getApplication()
        val outgoing = ExoPlayer.Builder(context).build()
        val incoming = ExoPlayer.Builder(context).build()
        outgoing.setMediaItem(MediaItem.fromUri("https://example.test/outgoing"))
        val failure = PlaybackException("Expired stream", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        var error: PlaybackException? = null
        val delegate = object : ForwardingPlayer(outgoing) {
            override fun getPlayerError() = error
        }
        val stable = DelegatingForwardingPlayer(delegate)
        stable.playbackIntent = { true }
        stable.preparingNextTrack = true
        try {
            val sessionView = ForwardingSimpleBasePlayer(stable)
            sessionView.addListener(object : Player.Listener {})
            assertEquals(Player.STATE_BUFFERING, sessionView.playbackState)
            error = failure
            stable.notifyPlaybackStateChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Player.STATE_IDLE, sessionView.playbackState)
            assertSame(failure, sessionView.playerError)
            incoming.setMediaItem(MediaItem.Builder().setMediaId("next").setUri("https://example.test/next").build())
            stable.swapDelegate(incoming)
            stable.notifyMediaItemChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(sessionView.playerError)
            assertEquals(Player.STATE_BUFFERING, sessionView.playbackState)
            assertEquals("next", sessionView.currentMediaItem?.mediaId)
        } finally { outgoing.release(); incoming.release() }
    }
}
