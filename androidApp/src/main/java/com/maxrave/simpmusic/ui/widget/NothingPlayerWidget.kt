package com.maxrave.simpmusic.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.widget.RemoteViews
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.MainActivity
import com.maxrave.simpmusic.R
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * NothingLyrics-style home screen player widget: spinning vinyl disc (rendered on a Canvas like
 * the reference `NothingLyricWidget`), progress bar and prev/play-pause/next transport, adapting
 * between the 1x4 strip / 2-row compact / 4x2 layouts. Fed from NothingPlayer's own
 * [SharedViewModel] instead of LyricRepository, and driven by a ~60fps frame loop (the reference
 * uses a Choreographer loop in its detection service).
 */
class NothingPlayerWidget : AppWidgetProvider(), KoinComponent {
    private val sharedViewModel: SharedViewModel by inject()
    private val dataStoreManager: DataStoreManager by inject()

    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playerViewsCache = ConcurrentHashMap<Int, Pair<String, RemoteViews>>()
    private val artworkCache = ConcurrentHashMap<String, Bitmap>()

    @Volatile
    private var lyricsOffset: Int = 0

    @Volatile
    private var frameJob: Job? = null

    @Volatile
    private var discRotation = 0f

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensureFrameLoop(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ensureFrameLoop(context)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        playerViewsCache.remove(appWidgetId)
        updateWidget(context, appWidgetManager, appWidgetId)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CONTROL) {
            when (intent.getStringExtra(EXTRA_CONTROL)) {
                CONTROL_PLAY, CONTROL_PAUSE -> sharedViewModel.onUIEvent(UIEvent.PlayPause)
                CONTROL_NEXT -> sharedViewModel.onUIEvent(UIEvent.Next)
                CONTROL_PREV -> sharedViewModel.onUIEvent(UIEvent.Previous)
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        frameJob?.cancel()
        frameJob = null
        playerViewsCache.clear()
        artworkCache.clear()
    }

    private fun ensureFrameLoop(context: Context) {
        if (frameJob?.isActive == true) return
        frameJob =
            frameScope.launch {
                launch {
                    runCatching { dataStoreManager.lyricsOffset.collect { lyricsOffset = it } }
                }
                while (isActive) {
                    val start = System.nanoTime()
                    val hasWidgets = pushPlayerFrame(context)
                    if (!hasWidgets) {
                        delay(500)
                    } else {
                        val playing = isPlayingNow()
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000
                        // 60fps disc rotation while playing, slow poll while paused.
                        val wait = if (playing) 16L - elapsedMs else 250L
                        if (wait > 0) delay(wait) else delay(1)
                    }
                }
                frameJob = null
            }
    }

    fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, NothingPlayerWidget::class.java)
        val appWidgetIds =
            runCatching { appWidgetManager.getAppWidgetIds(thisWidget) }.getOrDefault(IntArray(0))
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = buildPlayerViews(context, appWidgetId)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * One animation frame for every player widget. Returns false when no widget is installed so
     * the loop can slow to a poll. Rotation is only pushed while playing; content changes
     * (track, lyric line, artwork, play state) trigger a full rebuild regardless of state.
     */
    private fun pushPlayerFrame(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val ids = playerWidgetIds(context, manager)
        if (ids.isEmpty()) return false

        val key = contentKey()
        val playing = isPlayingNow()
        if (playing) {
            discRotation = (discRotation + DISC_DEG_PER_FRAME) % 360f
        }

        for (appWidgetId in ids) {
            val cached = playerViewsCache[appWidgetId]
            if (cached == null || cached.first != key) {
                updateWidget(context, manager, appWidgetId)
            } else if (playing) {
                val views = cached.second.clone() as RemoteViews
                applyFrameActions(views)
                manager.updateAppWidget(appWidgetId, views)
            }
        }
        return true
    }

    private fun playerWidgetIds(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, NothingPlayerWidget::class.java))

    private fun buildPlayerViews(context: Context, appWidgetId: Int): RemoteViews {
        val layout = playerWidgetLayout(context, appWidgetId)
        val views = RemoteViews(context.packageName, layout)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val screenData = sharedViewModel.nowPlayingScreenData.value
        val isPlaying = isPlayingNow()
        val lines = screenData.lyricsData?.lyrics?.lines.orEmpty()
        val currentMs = (sharedViewModel.timeline.value.current - lyricsOffset).coerceAtLeast(0L)
        val index = activeLineIndex(lines, currentMs)

        views.setImageViewBitmap(
            R.id.widget_player_disc,
            buildDiscBitmap(
                fetchArtwork(context, screenData.thumbnailURL),
                screenData.artistName,
                screenData.playlistName,
            ),
        )
        views.setOnClickPendingIntent(R.id.widget_player_root, openPendingIntent)

        val title = screenData.nowPlayingTitle
        views.setTextViewText(R.id.widget_title, title.ifBlank { "Nothing Player" })
        val lyricLine =
            when {
                title.isBlank() -> "Play music to see lyrics"
                lines.isEmpty() -> "Fetching lyrics..."
                else -> lines[index].words.ifBlank { "..." }
            }
        views.setTextViewText(R.id.widget_lyric_line1, lyricLine)
        if (layout != R.layout.widget_player_strip) {
            views.setTextViewText(
                R.id.widget_artist,
                screenData.artistName.ifBlank { "Open music player first" },
            )
        }

        views.setImageViewResource(
            R.id.widget_player_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        val playPausePendingIntent =
            controlPendingIntent(context, if (isPlaying) CONTROL_PAUSE else CONTROL_PLAY)
        views.setOnClickPendingIntent(R.id.widget_player_prev, controlPendingIntent(context, CONTROL_PREV))
        views.setOnClickPendingIntent(R.id.widget_player_disc_button, playPausePendingIntent)
        views.setOnClickPendingIntent(R.id.widget_player_disc, playPausePendingIntent)
        views.setOnClickPendingIntent(R.id.widget_player_play_pause, playPausePendingIntent)
        views.setOnClickPendingIntent(R.id.widget_player_next, controlPendingIntent(context, CONTROL_NEXT))

        applyFrameActions(views)
        playerViewsCache[appWidgetId] = contentKey() to views
        return views
    }

    private fun applyFrameActions(views: RemoteViews) {
        views.setFloat(R.id.widget_player_disc, "setRotation", discRotation)
        val timeline = sharedViewModel.timeline.value
        val duration = timeline.total
        val position = (timeline.current - lyricsOffset).coerceAtLeast(0L)
        val progress =
            if (duration > 0L) {
                ((position.coerceAtMost(duration) * PROGRESS_MAX) / duration).toInt()
            } else {
                0
            }
        views.setProgressBar(R.id.widget_player_progress, PROGRESS_MAX, progress, false)
    }

    /** Picks the layout that fits the launcher-provided cell size (1x4 strip / 2-row compact / big). */
    private fun playerWidgetLayout(context: Context, appWidgetId: Int): Int {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return when {
            minHeight in 1 until 80 -> R.layout.widget_player_strip
            minHeight in 80 until 150 -> R.layout.widget_player_compact
            else -> R.layout.widget_player
        }
    }

    private fun contentKey(): String {
        val screenData = sharedViewModel.nowPlayingScreenData.value
        val lines = screenData.lyricsData?.lyrics?.lines.orEmpty()
        val currentMs = (sharedViewModel.timeline.value.current - lyricsOffset).coerceAtLeast(0L)
        val index = activeLineIndex(lines, currentMs)
        return buildString {
            append(screenData.nowPlayingTitle)
            append('|')
            append(screenData.artistName)
            append('|')
            append(screenData.playlistName)
            append('|')
            append(index)
            append('|')
            append(isPlayingNow())
            append('|')
            append(lines.size)
            append('|')
            append(screenData.thumbnailURL?.hashCode())
        }
    }

    private fun activeLineIndex(lines: List<Line>, currentMs: Long): Int {
        var index = 0
        for ((i, line) in lines.withIndex()) {
            val start = line.startTimeMs.toLongOrNull() ?: continue
            if (start <= currentMs) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    private fun isPlayingNow(): Boolean =
        runCatching { sharedViewModel.controllerState.value.isPlaying }.getOrDefault(false)

    private fun controlPendingIntent(context: Context, control: String): PendingIntent {
        val intent =
            Intent(context, NothingPlayerWidget::class.java).apply {
                action = ACTION_CONTROL
                putExtra(EXTRA_CONTROL, control)
            }
        return PendingIntent.getBroadcast(
            context,
            control.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fetchArtwork(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        artworkCache[url]?.let { return it }
        return runBlocking {
            runCatching {
                val loader = ImageLoader(context)
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                val result = loader.execute(request)
                if (result is SuccessResult) result.image.toBitmap() else null
            }.getOrNull()
        }?.also { bitmap ->
            if (artworkCache.size > 12) artworkCache.clear()
            artworkCache[url] = bitmap
        }
    }

    /**
     * Exact visual replica of the reference home screen vinyl:
     * #1B1C1E body, #38393B ring, #3A3B3E diagonal guide lines, album art at 119/365 of the
     * disc diameter with a #535457 border, and the black center dot with white ring.
     */
    private fun buildDiscBitmap(artwork: Bitmap?, artist: String, album: String): Bitmap {
        val size = 240
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        canvas.drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1B1C1E.toInt()
        })
        canvas.drawCircle(center, center, center - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF38393B.toInt()
        })

        // Diagonal guide lines (same split around the center as the reference).
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3A3B3E.toInt()
            strokeWidth = 2f
        }
        val inset = size * 0.10f
        val gapStart = size * 0.39f
        val gapEnd = size * 0.61f
        canvas.drawLine(inset, size - inset, gapStart, size - gapStart, linePaint)
        canvas.drawLine(gapEnd, size - gapEnd, size - inset, inset, linePaint)

        // Match the two -45 degree labels printed on the home-screen disc.
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD7D7D7.toInt()
            textSize = size * 10f / 365f
            textAlign = Paint.Align.CENTER
        }
        fun drawLabel(text: String, x: Float, y: Float) {
            val saveCount = canvas.save()
            canvas.rotate(-45f, x, y)
            canvas.drawText(
                text,
                x,
                y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                labelPaint,
            )
            canvas.restoreToCount(saveCount)
        }
        drawLabel(
            artist.ifBlank { "UNKNOWN ARTIST" }.take(18).uppercase(),
            size * 102.5f / 365f,
            size * 264f / 365f,
        )
        drawLabel(
            album.ifBlank { "UNKNOWN ALBUM" }.take(16).uppercase(),
            size * 262.5f / 365f,
            size * 101f / 365f,
        )

        // Album art: 119/365 of the disc diameter, same ratio as the reference.
        val artSize = (size * 119f / 365f).roundToInt()
        val artLeft = (size - artSize) / 2f
        val artTop = (size - artSize) / 2f

        if (artwork != null) {
            val cropped = centerCrop(artwork, artSize, artSize)
            val clip = canvas.save()
            canvas.clipPath(Path().apply { addCircle(center, center, artSize / 2f, Path.Direction.CW) })
            canvas.drawBitmap(cropped, artLeft, artTop, null)
            canvas.restoreToCount(clip)
        } else {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = artSize * 0.15f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawText("NL", center, center - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        canvas.drawCircle(center, center, artSize / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF535457.toInt()
        })

        // Center dot: black with a thin white ring, like the reference.
        val dotRadius = size * 24f / 365f / 2f
        canvas.drawCircle(center, center, dotRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
        })
        canvas.drawCircle(center, center, dotRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFFFFFFFF.toInt()
        })

        return bitmap
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        var src = source
        if (src.config == Bitmap.Config.HARDWARE) {
            src = src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val sw = (width / scale).roundToInt().coerceAtMost(src.width)
        val sh = (height / scale).roundToInt().coerceAtMost(src.height)
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) / 2
        val cropped = Bitmap.createBitmap(src, sx, sy, sw, sh)
        return Bitmap.createScaledBitmap(cropped, width, height, true)
    }

    companion object {
        private const val ACTION_CONTROL = "com.maxrave.simpmusic.action.PLAYER_WIDGET_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_PLAY = "play"
        private const val CONTROL_PAUSE = "pause"
        private const val CONTROL_NEXT = "next"
        private const val CONTROL_PREV = "prev"

        // Reference spins 360 degrees every 8 seconds -> 45 deg/s -> 0.75 deg per 60fps frame.
        private const val DISC_DEG_PER_FRAME = 0.75f
        private const val PROGRESS_MAX = 10_000
    }
}
