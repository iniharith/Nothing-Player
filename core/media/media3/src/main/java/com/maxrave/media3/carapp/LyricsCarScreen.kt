package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Lyrics screen for Android Auto. Pulls the track's synced lyrics from the
 * shared [LyricsCanvasRepository] (LRCLIB) and shows the line currently being
 * sung plus the upcoming line, ticking off the player position while the
 * screen is on top.
 */
internal class LyricsCarScreen(
    carContext: CarContext,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val lyricsRepository: LyricsCanvasRepository by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings: com.maxrave.domain.manager.DataStoreManager by inject()
    private var lyricsJob: Job? = null
    private var offsetMs = 0
    private var lyrics: Lyrics? = null
    private var status: String = "Fetching lyrics..."

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch { settings.lyricsOffset.collect { offsetMs = it } }
        // Refetch when the playing track changes
        screenScope.launch {
            var lastMediaId: String? = null
            handler.nowPlaying.collect { item ->
                val mediaId = item?.mediaId
                if (mediaId != null && mediaId != lastMediaId) {
                    lastMediaId = mediaId
                    fetchLyrics()
                }
                invalidate()
            }
        }
        // Recompute the active line from the player position
        screenScope.launch {
            var lastRows = emptyList<String>()
            while (true) {
                val rows = lyricRows()
                if (rows != lastRows) {
                    lastRows = rows
                    invalidate()
                }
                delay(500)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val metadata = handler.nowPlaying.value?.metadata
        val title = metadata?.title?.takeIf { it.isNotBlank() } ?: "Nothing Playing"
        val artist = metadata?.artist?.takeIf { it.isNotBlank() }

        val itemListBuilder =
            ItemList
                .Builder()
                .setNoItemsMessage(status)
        lyricRows().forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                itemListBuilder.addItem(
                    Row
                        .Builder()
                        .setTitle(if (index == 0) carContext.nowPlayingTitle(text) else text)
                        .build(),
                )
            }
        }

        return ListTemplate
            .Builder()
            .setHeader(
                Header
                    .Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(title)
                    .apply { artist?.let { setSubtitle(it) } }
                    .build(),
            ).setSingleList(itemListBuilder.build())
            .build()
    }

    private fun fetchLyrics() {
        lyricsJob?.cancel()
        lyricsJob = screenScope.launch {
            val item = handler.nowPlaying.value
            val title = item?.metadata?.title
            val artist = item?.metadata?.artist
            if (title.isNullOrBlank() && artist.isNullOrBlank()) {
                lyrics = null
                status = "No lyrics available"
                invalidate()
                return@launch
            }
            status = "Fetching lyrics..."
            lyrics = null
            val duration = handler.getPlayerDuration().takeIf { it > 0 }?.div(1000)?.toInt()
            lyrics = try { withTimeoutOrNull(20_000) {
                lyricsRepository
                    .getLrclibLyricsData(artist.orEmpty(), title.orEmpty(), duration)
                    .firstOrNull()?.data
            } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (handler.nowPlaying.value?.mediaId != item?.mediaId) return@launch
            status =
                when {
                    lyrics?.lines.isNullOrEmpty() -> "No synced lyrics found"
                    else -> "Lyrics"
                }
            invalidate()
        }
    }

    /** [current, next] lines for the current position, with the singing line first. */
    private fun lyricRows(): List<String> {
        val lines = lyrics?.lines.orEmpty()
        if (lines.isEmpty()) return emptyList()
        if (lyrics?.syncType != "LINE_SYNCED") return emptyList()
        val window = com.maxrave.media3.service.lyricWindow(lines, handler.getProgress() - offsetMs)
            ?: return emptyList()
        return listOf(window.first, window.second)
    }
}
