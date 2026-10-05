package com.maxrave.media3.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.toLyrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap

/** A metadata-only view of the player. Lyric changes never replace or prepare a track. */
@UnstableApi
internal class CarLyricsPlayer(
    private val base: Player,
    private val settings: DataStoreManager,
    private val repository: LyricsCanvasRepository,
) : ForwardingPlayer(base) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connected = MutableStateFlow(false)
    private val track = MutableStateFlow(base.currentMediaItem?.mediaId)
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    private var lyricId: String? = null
    private var display: Pair<String, String>? = null
    private val observer = object : Player.Listener {
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
            clear()
            track.value = item?.mediaId
        }
    }

    init {
        base.addListener(observer)
        scope.launch {
            combine(connected, settings.androidAutoLyrics, track) { car, enabled, id ->
                id.takeIf { car && enabled == DataStoreManager.TRUE }
            }.distinctUntilChanged().collectLatest { id ->
                clear()
                if (id == null) return@collectLatest
                val metadata = base.mediaMetadata
                val duration = base.duration.takeIf { it > 0 }?.div(1000)?.toInt()
                val lyrics = try { withTimeoutOrNull(20_000) {
                    val saved = repository.getSavedLyrics(id).firstOrNull()?.toLyrics()
                    if (saved?.syncType == "LINE_SYNCED" && !saved.lines.isNullOrEmpty()) saved else {
                        val provider = settings.lyricsProvider.first()
                        val result = if (provider == DataStoreManager.YOUTUBE) {
                            repository.getYouTubeCaption(settings.youtubeSubtitleLanguage.first(), id).firstOrNull()?.data?.first
                        } else when (provider) {
                            DataStoreManager.NOTHINGPLAYER -> repository.getNothingPlayerLyrics(id)
                            DataStoreManager.BETTER_LYRICS -> repository.getBetterLyrics(metadata.artist.toString(), metadata.title.toString(), duration)
                            else -> repository.getLrclibLyricsData(metadata.artist?.toString().orEmpty(), metadata.title?.toString().orEmpty(), duration)
                        }.firstOrNull()?.data
                        if (result?.syncType == "LINE_SYNCED") result else
                            repository.getLrclibLyricsData(metadata.artist?.toString().orEmpty(), metadata.title?.toString().orEmpty(), duration).firstOrNull()?.data
                    }
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (lyrics?.syncType != "LINE_SYNCED") return@collectLatest
                combine(settings.lyricsOffset, ticker()) { offset, _ -> offset }.collect { offset ->
                    if (base.currentMediaItem?.mediaId != id) return@collect
                    val next = lyricWindow(lyrics.lines.orEmpty(), base.currentPosition - offset)
                    if (display != next || lyricId != id) {
                        lyricId = id
                        display = next
                        notifyMetadata()
                    }
                }
            }
        }
    }

    fun setCarConnected(value: Boolean) { connected.value = value }

    private fun ticker() = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(300) } }

    override fun getMediaMetadata(): MediaMetadata = project(base.mediaMetadata)

    private fun project(original: MediaMetadata): MediaMetadata {
        val lines = display.takeIf { lyricId == base.currentMediaItem?.mediaId } ?: return original
        return original.buildUpon().setTitle(lines.first).setDisplayTitle(lines.first)
            .setArtist(lines.second).setSubtitle(lines.second).build()
    }

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val bridge = object : Player.Listener by listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listener.onEvents(this@CarLyricsPlayer, events)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                listener.onMediaMetadataChanged(project(mediaMetadata))
            }
        }
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
    val timed = lines.mapNotNull { line -> line.startTimeMs.toLongOrNull()?.let { it to line.words.trim() } }
        .sortedBy { it.first }
    val index = timed.indexOfLast { it.first <= positionMs }
    if (index < 0 || timed[index].second.isBlank()) return null
    return timed[index].second to timed.getOrNull(index + 1)?.second.orEmpty()
}
