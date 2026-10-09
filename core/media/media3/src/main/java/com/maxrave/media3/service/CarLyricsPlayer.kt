package com.maxrave.media3.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.songLyricsOffset
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.media3.exoplayer.forwardingListener
import com.maxrave.domain.utils.toLyrics
import com.maxrave.domain.utils.toSyncedLyrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap

/** A metadata-only view of the player. Lyric changes never replace or prepare a track. */
@UnstableApi
internal class CarLyricsPlayer(
    private val base: Player,
    private val settings: DataStoreManager,
    private val repository: LyricsCanvasRepository,
    private val setPlaybackIntent: ((Boolean) -> Unit)? = null,
) : ForwardingPlayer(base) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connected = MutableStateFlow(false)
    private val track = MutableStateFlow(base.currentMediaItem?.mediaId)
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    private var lyricId: String? = null
    private var display: Pair<String, String>? = null
    private val observer = object : Player.Listener {
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
            if (track.value == item?.mediaId) return
            clear()
            track.value = item?.mediaId
        }
    }

    init {
        base.addListener(observer)
        scope.launch {
            combine(connected, settings.androidAutoLyrics, track, settings.lyricsProvider, settings.youtubeSubtitleLanguage) { car, enabled, id, provider, language ->
                Triple(id.takeIf { car && enabled == DataStoreManager.TRUE }, provider, language)
            }.distinctUntilChanged().collectLatest { id ->
                clear()
                val mediaId = id.first ?: return@collectLatest
                var lyrics: Lyrics? = attempt { repository.getSavedLyrics(mediaId).firstOrNull()?.toLyrics() }
                val providers = (listOf(id.second, DataStoreManager.LRCLIB, DataStoreManager.NOTHINGPLAYER, DataStoreManager.BETTER_LYRICS)).distinct()
                for (retry in 0..2) {
                    if (lyrics != null) break
                    if (retry > 0) delay(if (retry == 1) 3_000 else 15_000)
                    // Read metadata again after a retry; the initial decoder may not yet have duration.
                    val metadata = base.mediaMetadata
                    val duration = base.duration.takeIf { it > 0 }?.div(1000)?.toInt()
                    for (provider in providers) {
                        lyrics = attempt {
                            when (provider) {
                                DataStoreManager.YOUTUBE -> repository.getYouTubeCaption(id.third, mediaId).firstOrNull()?.data?.first
                                DataStoreManager.NOTHINGPLAYER -> repository.getNothingPlayerLyrics(mediaId).firstOrNull()?.data
                                DataStoreManager.BETTER_LYRICS -> repository.getBetterLyrics(metadata.artist?.toString().orEmpty(), metadata.title?.toString().orEmpty(), duration).firstOrNull()?.data
                                else -> repository.getLrclibLyricsData(metadata.artist?.toString().orEmpty(), metadata.title?.toString().orEmpty(), duration).firstOrNull()?.data
                            }
                        }
                        if (lyrics != null) break
                    }
                }
                val synced = lyrics ?: return@collectLatest
                combine(settings.songLyricsOffset(mediaId), ticker()) { offset, _ -> offset }.collect { offset ->
                    if (base.currentMediaItem?.mediaId != mediaId) return@collect
                    val next = lyricWindow(synced.lines.orEmpty(), base.currentPosition - offset)
                    if (display != next || lyricId != mediaId) {
                        lyricId = mediaId
                        display = next
                        notifyMetadata()
                    }
                }
            }
        }
    }

    fun setCarConnected(value: Boolean) { connected.value = value }

    private suspend fun attempt(fetch: suspend () -> Lyrics?): Lyrics? = try {
        withTimeoutOrNull(8_000) { fetch()?.toSyncedLyrics()?.takeIf {
            it.syncType == "LINE_SYNCED" && it.lines.orEmpty().any { line -> line.startTimeMs.toLongOrNull() != null && line.words.isNotBlank() }
        } }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val command = setPlaybackIntent
        if (command != null) command(playWhenReady) else super.setPlayWhenReady(playWhenReady)
    }

    private fun ticker() = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(300) } }

    override fun getMediaMetadata(): MediaMetadata = project(base.mediaMetadata)

    private fun project(original: MediaMetadata): MediaMetadata {
        val lines = display.takeIf { lyricId == base.currentMediaItem?.mediaId } ?: return original
        return original.buildUpon().setTitle(lines.first).setDisplayTitle(lines.first)
            .setArtist(lines.second).setSubtitle(lines.second).build()
    }

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val bridge = forwardingListener(listener,
            metadata = ::project,
            onEvents = { events -> listener.onEvents(this@CarLyricsPlayer, events) },
        )
        listeners[listener] = bridge
        super.addListener(bridge)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private fun clear() {
        if (display == null) return
        display = null
        lyricId = null
        notifyMetadata()
    }

    private fun notifyMetadata() {
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        listeners.keys.toList().forEach {
            it.onMediaMetadataChanged(mediaMetadata)
            it.onEvents(this, events)
        }
    }

    fun close() {
        scope.cancel()
        clear()
        base.removeListener(observer)
        listeners.keys.toList().forEach(::removeListener)
    }
}

/** Keep the original metadata during intros, gaps and when synced lyrics are unavailable. */
internal fun lyricWindow(lines: List<Line>, positionMs: Long): Pair<String, String>? {
    val timed = lines.mapNotNull { line -> line.startTimeMs.toLongOrNull()?.let { Triple(it, line.words.trim(), line.endTimeMs.toLongOrNull()) } }
        .sortedBy { it.first }
    val index = timed.indexOfLast { it.first <= positionMs }
    if (index < 0 || timed[index].second.isBlank()) return null
    val end = timed[index].third
    if (end != null && end > timed[index].first && positionMs >= end) return null
    return timed[index].second to timed.getOrNull(index + 1)?.second.orEmpty()
}
