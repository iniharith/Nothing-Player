package com.maxrave.media3.exoplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class ForwardingListenerTest {
    @Test fun individualJavaDefaultCallbacksAreForwardedAndIntentIsNormalized() {
        var height = 0
        var mediaId: String? = null
        var intent: Boolean? = null
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { height = size.height }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { mediaId = item?.mediaId }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { intent = value }
        }
        val bridge = forwardingListener(listener, playbackIntent = { false }, onEvents = {})
        bridge.onVideoSizeChanged(VideoSize(1920, 1080))
        bridge.onMediaItemTransition(MediaItem.Builder().setMediaId("video").build(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        bridge.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(1080, height)
        assertEquals("video", mediaId)
        assertEquals(false, intent)
    }
}
