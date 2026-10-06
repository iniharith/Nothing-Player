package com.maxrave.media3.exoplayer

import android.os.Looper
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
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
class MediaSessionHandoffTest {
    @Test fun recoverableIdleGapKeepsBufferingButARealStopDoesNot() {
        val delegate = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val stable = DelegatingForwardingPlayer(delegate)
        var intent = true
        stable.playbackIntent = { intent }
        stable.preparingNextTrack = true
        try {
            assertEquals(Player.STATE_IDLE, delegate.playbackState)
            assertEquals(Player.STATE_BUFFERING, stable.playbackState)
            intent = false
            assertEquals(Player.STATE_IDLE, stable.playbackState)
            intent = true
            stable.preparingNextTrack = false
            assertEquals(Player.STATE_IDLE, stable.playbackState)
        } finally { delegate.release() }
    }

    @Test fun controllerKeepsTimelineAndPlaybackIntentWhenIncomingPlayerStartsPaused() {
        val context = RuntimeEnvironment.getApplication()
        val outgoing = ExoPlayer.Builder(context).build()
        val incoming = ExoPlayer.Builder(context).build()
        outgoing.setMediaItem(MediaItem.Builder().setMediaId("first").setUri("https://example.test/first").build())
        incoming.setMediaItem(MediaItem.Builder().setMediaId("second").setUri("https://example.test/second").build())
        fun buffering(player: Player) = object : ForwardingPlayer(player) {
            override fun getPlaybackState() = Player.STATE_BUFFERING
        }
        val stable = DelegatingForwardingPlayer(buffering(outgoing))
        stable.playbackIntent = { true }
        // Exercise the nested forwarding used by Cast and the lyrics view too.
        val session = MediaSession.Builder(context, ForwardingSimpleBasePlayer(stable)).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        try {
            repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
            assertTrue("Controller must connect", future.isDone)
            val controller = future.get()
            try {
                stable.swapDelegate(buffering(incoming))
                stable.notifyMediaItemChanged()
                repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
                assertFalse("Incoming decoder is initially paused", incoming.playWhenReady)
                assertTrue("The session must retain user playback intent", controller.playWhenReady)
                assertEquals("second", controller.currentMediaItem?.mediaId)
                assertFalse("Media notifications require a non-empty timeline", controller.currentTimeline.isEmpty)
                assertEquals(Player.STATE_BUFFERING, controller.playbackState)
            } finally { controller.release() }
        } finally {
            session.release()
            outgoing.release()
            incoming.release()
        }
    }

    @Test fun temporaryDelegatePauseDoesNotBecomeUserPauseAndExplicitPauseStillWorks() {
        val delegate = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val stable = DelegatingForwardingPlayer(delegate)
        var intent = true
        stable.playbackIntent = { intent }
        val reported = mutableListOf<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { reported += playWhenReady }
        }
        stable.addListener(listener)
        try {
            delegate.playWhenReady = true
            delegate.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(reported.isNotEmpty())
            assertTrue("Decoder pause during handoff must not cancel preparation", reported.all { it })
            intent = false
            stable.notifyMediaItemChanged()
            assertFalse(reported.last())
            stable.removeListener(listener)
            val count = reported.size
            stable.notifyMediaItemChanged()
            assertEquals("Removed listeners must not receive notifications", count, reported.size)
        } finally { delegate.release() }
    }
}
