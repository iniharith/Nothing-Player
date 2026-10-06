package com.maxrave.media3.exoplayer

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.FlagSet
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.logger.Logger

private const val TAG = "DelegatingForwardingPlayer"

/**
 * A [ForwardingPlayer] that allows runtime swapping of its underlying delegate.
 *
 * Used for MediaSession integration - provides a stable [Player] reference while
 * [CrossfadeExoPlayerAdapter] cycles through different ExoPlayer instances.
 *
 * Pattern: Track all externally added listeners, then during swap:
 * 1. Remove them via [ForwardingPlayer.removeListener] (cleans up ForwardingListener wrappers)
 * 2. Switch the mutable routing target
 * 3. Re-add them via [ForwardingPlayer.addListener] (creates new ForwardingListener wrappers for new delegate)
 *
 * Additionally, since each underlying ExoPlayer only has a single MediaItem,
 * playlist navigation methods (hasNext/hasPrevious, seek, mediaItemCount, etc.)
 * are overridden to delegate to [PlaylistNavigationProvider] — which is backed by
 * [CrossfadeExoPlayerAdapter]'s internal playlist. This ensures MediaSession reports
 * correct available commands (SEEK_TO_NEXT, SEEK_TO_PREVIOUS) and shows
 * next/previous buttons in the system notification.
 */
@UnstableApi
internal class DelegatingForwardingPlayer private constructor(
    private val routing: PlayerRouting,
) : ForwardingPlayer(routing.proxy) {
    constructor(initialDelegate: Player) : this(PlayerRouting(initialDelegate))

    private class PlayerRouting(var target: Player) : java.lang.reflect.InvocationHandler {
        val proxy: Player = java.lang.reflect.Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java), this,
        ) as Player

        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Playback delegate router"
                    else -> null
                }
            }
            return try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw error.targetException
            }
        }
    }
    // ========== Playlist Navigation Provider ==========

    /**
     * Provides playlist-level navigation information to the ForwardingPlayer.
     *
     * Each underlying ExoPlayer only has 1 MediaItem, so ExoPlayer's own
     * hasNextMediaItem()/hasPreviousMediaItem() always return false.
     * This provider bridges the gap, letting MediaSession see the full playlist state.
     */
    interface PlaylistNavigationProvider {
        fun hasNextMediaItem(): Boolean

        fun hasPreviousMediaItem(): Boolean

        fun seekToNext()

        fun seekToPrevious()

        /**
         * Always advances to the previous media item — bypasses the 3-second
         * "seek to start" rule used by [seekToPrevious]. Implementations may
         * default to [seekToPrevious] if the distinction is irrelevant.
         */
        fun seekToPreviousMediaItem() = seekToPrevious()

        fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)

        fun prepare()

        fun play()

        fun pause()

        fun stop()

        fun clearMediaItems()

        fun currentQueueIndex(): Int
    }

    /**
     * Set by [CrossfadeExoPlayerAdapter] to provide playlist navigation.
     * When null, all navigation methods fall back to the underlying ExoPlayer (single-item behavior).
     */
    var playlistNavigationProvider: PlaylistNavigationProvider? = null

    // Session/controller commands must mutate the adapter that owns the real queue.
    // Timeline getters still describe the single-track delegate to satisfy Media3.
    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, true)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        val provider = playlistNavigationProvider
        if (provider == null) {
            super.setMediaItems(mediaItems, resetPosition)
        } else {
            provider.setMediaItems(
                mediaItems,
                if (resetPosition) 0 else provider.currentQueueIndex(),
                if (resetPosition) C.TIME_UNSET else currentPosition,
            )
        }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val provider = playlistNavigationProvider
        if (provider == null) super.setMediaItems(mediaItems, startIndex, startPositionMs)
        else provider.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), 0, C.TIME_UNSET)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        setMediaItems(listOf(mediaItem), 0, if (resetPosition) C.TIME_UNSET else currentPosition)

    override fun prepare() {
        val provider = playlistNavigationProvider
        if (provider == null) super.prepare() else provider.prepare()
    }

    override fun play() {
        val provider = playlistNavigationProvider
        if (provider == null) super.play() else provider.play()
    }

    override fun pause() {
        val provider = playlistNavigationProvider
        if (provider == null) super.pause() else provider.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val provider = playlistNavigationProvider
        if (provider == null) super.setPlayWhenReady(playWhenReady)
        else if (playWhenReady) provider.play() else provider.pause()
    }

    override fun stop() {
        val provider = playlistNavigationProvider
        if (provider == null) super.stop() else provider.stop()
    }

    override fun clearMediaItems() {
        val provider = playlistNavigationProvider
        if (provider == null) super.clearMediaItems() else provider.clearMediaItems()
    }

    // ========== Playback-Ended Suppression ==========

    /**
     * When true, [getPlaybackState] remaps [Player.STATE_ENDED] to [Player.STATE_BUFFERING].
     *
     * This prevents [androidx.media3.session.MediaLibraryService] from dropping the
     * foreground-service notification during the gap between one ExoPlayer finishing
     * and the next ExoPlayer being swapped in via [swapDelegate].
     *
     * Set by [CrossfadeExoPlayerAdapter] before a track-end transition starts,
     * cleared after the next player is swapped in and playing.
     */
    @Volatile
    var suppressPlaybackEnded = false

    var transientAudioFocusLoss = false

    // The adapter owns playback intent; a replacement ExoPlayer starts paused.
    var playbackIntent: (() -> Boolean)? = null
    var preparingNextTrack = false

    override fun getPlayWhenReady(): Boolean = playbackIntent?.invoke() ?: (transientAudioFocusLoss || super.getPlayWhenReady())

    override fun getPlaybackSuppressionReason(): Int =
        if (transientAudioFocusLoss) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        else super.getPlaybackSuppressionReason()


    override fun getPlaybackState(): Int {
        val state = super.getPlaybackState()
        if (preparingNextTrack && playWhenReady && (state == Player.STATE_IDLE || state == Player.STATE_ENDED)) {
            return Player.STATE_BUFFERING
        }
        if (state == Player.STATE_ENDED && suppressPlaybackEnded) {
            return Player.STATE_BUFFERING
        }
        return state
    }

    // ========== Listener Tracking ==========

    // Track all externally registered listeners so we can re-register them after delegate swap
    private val trackedListeners = mutableListOf<Player.Listener>()
    private val listenerBridges = java.util.IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        if (listenerBridges.containsKey(listener)) return
        val bridge = object : Player.Listener by listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listener.onPlayWhenReadyChanged(this@DelegatingForwardingPlayer.playWhenReady, reason)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                listener.onPlaybackStateChanged(this@DelegatingForwardingPlayer.playbackState)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                listener.onPlaybackSuppressionReasonChanged(this@DelegatingForwardingPlayer.playbackSuppressionReason)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                listener.onEvents(this@DelegatingForwardingPlayer, events)
            }
        }
        listenerBridges[listener] = bridge
        trackedListeners.add(listener)
        super.addListener(bridge)
    }

    override fun removeListener(listener: Player.Listener) {
        trackedListeners.remove(listener)
        listenerBridges.remove(listener)?.let { super.removeListener(it) }
    }

    // ========== Video Surface Tracking ==========
    // Track the current video output so it can be re-attached when the delegate is swapped.
    // Without this, video stops rendering after a delegate swap because the new ExoPlayer
    // instance never receives setVideoSurfaceView/setVideoSurface/etc.

    private sealed class VideoOutput {
        data class SurfaceViewOutput(val surfaceView: SurfaceView) : VideoOutput()

        data class TextureViewOutput(val textureView: TextureView) : VideoOutput()

        data class SurfaceOutput(val surface: Surface) : VideoOutput()

        data class SurfaceHolderOutput(val surfaceHolder: SurfaceHolder) : VideoOutput()
    }

    private var currentVideoOutput: VideoOutput? = null

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        currentVideoOutput = surfaceView?.let { VideoOutput.SurfaceViewOutput(it) }
        super.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        currentVideoOutput = textureView?.let { VideoOutput.TextureViewOutput(it) }
        super.setVideoTextureView(textureView)
    }

    override fun setVideoSurface(surface: Surface?) {
        currentVideoOutput = surface?.let { VideoOutput.SurfaceOutput(it) }
        super.setVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        currentVideoOutput = surfaceHolder?.let { VideoOutput.SurfaceHolderOutput(it) }
        super.setVideoSurfaceHolder(surfaceHolder)
    }

    override fun clearVideoSurface() {
        currentVideoOutput = null
        super.clearVideoSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        if (currentVideoOutput is VideoOutput.SurfaceOutput &&
            (currentVideoOutput as VideoOutput.SurfaceOutput).surface === surface
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurface(surface)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (currentVideoOutput is VideoOutput.SurfaceViewOutput &&
            (currentVideoOutput as VideoOutput.SurfaceViewOutput).surfaceView === surfaceView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceView(surfaceView)
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (currentVideoOutput is VideoOutput.TextureViewOutput &&
            (currentVideoOutput as VideoOutput.TextureViewOutput).textureView === textureView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoTextureView(textureView)
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (currentVideoOutput is VideoOutput.SurfaceHolderOutput &&
            (currentVideoOutput as VideoOutput.SurfaceHolderOutput).surfaceHolder === surfaceHolder
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceHolder(surfaceHolder)
    }

    /**
     * Clear video output from a specific player instance.
     * Must be called on the OLD delegate before swapping, so the native surface
     * is disconnected from the old MediaCodec before the new player tries to connect.
     */
    private fun clearVideoOutputFromPlayer(player: Player) {
        if (currentVideoOutput != null) {
            Logger.d(TAG, "Clearing video surface from old delegate before swap")
            try {
                player.clearVideoSurface()
            } catch (e: Exception) {
                Logger.w(TAG, "Error clearing video surface from old delegate: ${e.message}")
            }
        }
    }

    /**
     * Re-attach the tracked video output to the current delegate.
     * Called after [swapDelegate] to ensure video continues rendering on the new ExoPlayer.
     */
    private fun reAttachVideoOutput() {
        when (val output = currentVideoOutput) {
            is VideoOutput.SurfaceViewOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceView to new delegate")
                routing.target.setVideoSurfaceView(output.surfaceView)
            }
            is VideoOutput.TextureViewOutput -> {
                Logger.d(TAG, "Re-attaching TextureView to new delegate")
                routing.target.setVideoTextureView(output.textureView)
            }
            is VideoOutput.SurfaceOutput -> {
                Logger.d(TAG, "Re-attaching Surface to new delegate")
                routing.target.setVideoSurface(output.surface)
            }
            is VideoOutput.SurfaceHolderOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceHolder to new delegate")
                routing.target.setVideoSurfaceHolder(output.surfaceHolder)
            }
            null -> {
                // No video output to re-attach
            }
        }
    }

    // ========== Playlist Navigation Overrides ==========

    override fun getAvailableCommands(): Player.Commands {
        val baseCommands = super.getAvailableCommands()
        val nav = playlistNavigationProvider ?: return baseCommands

        val builder = baseCommands.buildUpon()

        // Always add seek-to-previous (allows seek to start of track even if no previous item)
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)

        if (nav.hasNextMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (nav.hasPreviousMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }

        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        val nav = playlistNavigationProvider
        if (nav != null) {
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                    return nav.hasNextMediaItem()
                Player.COMMAND_SEEK_TO_PREVIOUS ->
                    return true // Always allow seeking to start of current track
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    return nav.hasPreviousMediaItem()
            }
        }
        return super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean =
        playlistNavigationProvider?.hasNextMediaItem() ?: super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean =
        playlistNavigationProvider?.hasPreviousMediaItem() ?: super.hasPreviousMediaItem()

    override fun seekToNext() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNext()
        }
    }

    override fun seekToPrevious() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToPrevious()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNextMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            // Bypass the 3-second "seek to start" rule used by seekToPrevious().
            nav.seekToPreviousMediaItem()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    // NOTE: Do NOT override getMediaItemCount() or getCurrentMediaItemIndex() here.
    // These must remain consistent with the underlying ExoPlayer's Timeline (1 item, index 0).
    // Media3's PlayerWrapper.createPositionInfo() validates that currentMediaItemIndex < timeline.windowCount.
    // If we return the adapter's playlist index (e.g. 5) but Timeline only has 1 window, it crashes.

    // ========== Delegate Swap ==========

    /**
     * Swap the underlying delegate player.
     *
     * This properly migrates all registered listeners (e.g., MediaSession's listener)
     * from the old delegate to the new one.
     */
    fun swapDelegate(newDelegate: Player) {
        if (routing.target === newDelegate) return

// 1. Snapshot current listeners
        val listenersToReAdd = trackedListeners.toList()

        // 2. Clear video surface from OLD delegate BEFORE swapping.
        //    The native surface can only be connected to one MediaCodec at a time.
        //    If we don't clear it here, the new player's MediaCodec.setSurface() will fail
        //    with "already connected" → IllegalArgumentException crash.
        clearVideoOutputFromPlayer(routing.target)

        // 3. Remove all listeners from old delegate (ForwardingPlayer removes ForwardingListener wrappers)
        listenersToReAdd.forEach { listener ->
            try {
                listenerBridges[listener]?.let { super.removeListener(it) }
            } catch (e: Exception) {
                Logger.w(TAG, "Error removing listener during swap: ${e.message}")
            }
        }

        // 4. Switch the routing target without mutating Media3 final fields
        routing.target = newDelegate

        // 5. Re-add all listeners (ForwardingPlayer creates new ForwardingListener wrappers for new delegate)
        listenersToReAdd.forEach { listener ->
            listenerBridges[listener]?.let { super.addListener(it) }
        }

        // 6. Re-attach video surface to the new delegate
        //    Without this, video stops rendering because the new ExoPlayer
        //    never received setVideoSurfaceView/setVideoSurface/etc.
        reAttachVideoOutput()

        // 6. Verify
        if (routing.target !== newDelegate) {
            Logger.e(TAG, "Delegate swap verification FAILED - routing.target is not the new delegate!")
        } else {
            Logger.d(TAG, "Delegate swapped successfully")
        }
    }

    // ========== Manual Event Dispatch ==========

    /**
     * Manually notify all tracked listeners about a media item change.
     *
     * This is needed after [swapDelegate] because the new delegate may already have
     * a MediaItem set and be playing — meaning listeners missed the initial
     * [Player.Listener.onMediaItemTransition] and [Player.Listener.onMediaMetadataChanged] events.
     *
     * Also dispatches [Player.Listener.onAvailableCommandsChanged] so MediaSession
     * re-evaluates which notification buttons to show (next/previous).
     *
     * Primary use case: crossfade transitions, where the secondary player is prepared
     * (with MediaItem + prepare()) before the ForwardingPlayer is swapped to it.
     * MediaSession uses these events to update the system notification metadata.
     */
    fun notifyMediaItemChanged() {
        val player = routing.target
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        val metadata = player.mediaMetadata
        val commands = getAvailableCommands()

        Logger.d(TAG, "Manually notifying ${trackedListeners.size} listeners about media item change: ${metadata.title}")

        val events = Player.Events(
            FlagSet.Builder()
                .add(Player.EVENT_TIMELINE_CHANGED)
                .add(Player.EVENT_MEDIA_ITEM_TRANSITION)
                .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                .add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                .add(Player.EVENT_IS_PLAYING_CHANGED)
                .add(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
                .add(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
                .build(),
        )
        trackedListeners.toList().forEach { listener ->
            try {
                listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                listener.onMediaMetadataChanged(metadata)
                listener.onAvailableCommandsChanged(commands)
                listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                listener.onPlaybackStateChanged(playbackState)
                listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                listener.onIsPlayingChanged(isPlaying)
                listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                listener.onPlaybackParametersChanged(playbackParameters)
                listener.onEvents(this, events)
            } catch (e: Exception) {
                Logger.w(TAG, "Error notifying listener about media item change: ${e.message}")
            }
        }
    }
}
