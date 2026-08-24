package com.nothingplayer.app.lyrics

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values
import com.nothingplayer.app.ui.icon.ClassicPause
import com.nothingplayer.app.ui.icon.ClassicPlayArrow
import com.nothingplayer.app.ui.icon.SimpIcons
import com.nothingplayer.app.ui.icon.SkipNext
import com.nothingplayer.app.ui.icon.SkipPrevious
import com.nothingplayer.app.viewModel.SharedViewModel
import com.nothingplayer.app.viewModel.UIEvent
import org.koin.android.ext.android.inject
import kotlin.math.abs

// Pure black-and-white (Rec.601 luma) filter for the AOD artwork.
private val aodGrayscaleFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

private data class TimedLineIndex(
    val index: Int,
    val startTimeMs: Long,
)

// Last line whose startTimeMs <= nowMs, using its ORIGINAL list index.
// Mirrors LyricsView's lookup so the AOD highlight tracks exactly like the in-app lyrics.
private fun List<TimedLineIndex>.activeIndexAt(nowMs: Long): Int {
    if (isEmpty()) return -1
    if (nowMs < first().startTimeMs) return -1
    var lo = 0
    var hi = size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startTimeMs <= nowMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return if (ans >= 0) this[ans].index else -1
}

class LyricsOnlyActivity : ComponentActivity() {
    private val sharedViewModel: SharedViewModel by inject()
    private val dataStoreManager: DataStoreManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        applyImmersiveMode()

        setContent {
            MaterialTheme(
                colorScheme =
                    androidx.compose.material3.darkColorScheme(
                        background = Color.Black,
                        surface = Color.Black,
                        primary = Color(0xFF78E3C8),
                        onBackground = Color.White,
                        onSurface = Color.White,
                    ),
            ) {
                val screenData by sharedViewModel.nowPlayingScreenData.collectAsState()
                val controllerState by sharedViewModel.controllerState.collectAsState()
                // Keep the raw State reference: the timeline ticks every position update,
                // so it must only be read inside the derived state below. Reading it here
                // would recompose the whole lyrics screen on every tick (the lag/stuck scroll).
                val timelineState = sharedViewModel.timeline.collectAsState()
                val lyricsOffset = dataStoreManager.lyricsOffset.collectAsState(initial = 0)

                // Only recompose the lyrics screen when the ACTIVE lyric index actually changes
                // (mirrors NothingLyrics' low-frequency poll + change-only index updates).
                val timedLineIndexes =
                    remember(screenData.lyricsData?.lyrics?.lines) {
                        (screenData.lyricsData?.lyrics?.lines ?: emptyList())
                            .mapIndexedNotNull { index, line ->
                                line.startTimeMs.toLongOrNull()?.let { TimedLineIndex(index, it) }
                            }.sortedBy { it.startTimeMs }
                    }
                val currentLyricIndex by remember(timedLineIndexes, screenData) {
                    derivedStateOf {
                        val now = timelineState.value.current - lyricsOffset.value
                        if (now <= 0L) -1 else timedLineIndexes.activeIndexAt(now)
                    }
                }

                val lyricsLines = screenData.lyricsData?.lyrics?.lines ?: emptyList()
                val canvasData = screenData.canvasData
                val albumArtUrl = screenData.thumbnailURL ?: canvasData?.takeIf { !it.isVideo }?.url
                val canvasVideoUrl = canvasData?.takeIf { it.isVideo }?.url

                val showAlbumArtPref = dataStoreManager.aodShowAlbumArt.collectAsState(initial = Values.TRUE)
                val aodColorHex = dataStoreManager.aodLyricsColor.collectAsState(initial = "FFFFFF")
                val aodSize = dataStoreManager.aodLyricsSize.collectAsState(initial = 30f)

                val lyricColor =
                    remember(aodColorHex.value) {
                        runCatching {
                            Color(android.graphics.Color.parseColor("#${aodColorHex.value}"))
                        }.getOrDefault(Color.White)
                    }

                LyricsOnlyScreen(
                    lyricIndex = currentLyricIndex,
                    lyricLines = lyricsLines,
                    albumArtUrl = albumArtUrl,
                    canvasVideoUrl = canvasVideoUrl,
                    showAlbumArt = showAlbumArtPref.value == Values.TRUE,
                    lyricColor = lyricColor,
                    lyricSizeSp = aodSize.value,
                    isPlaying = controllerState.isPlaying,
                    isPreviousAvailable = controllerState.isPreviousAvailable,
                    isNextAvailable = controllerState.isNextAvailable,
                    onPrevious = { sharedViewModel.onUIEvent(UIEvent.Previous) },
                    onPlayPause = { sharedViewModel.onUIEvent(UIEvent.PlayPause) },
                    onNext = { sharedViewModel.onUIEvent(UIEvent.Next) },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LyricsOnlyActivity::class.java))
        }
    }
}

@Composable
private fun LyricsOnlyScreen(
    lyricIndex: Int,
    lyricLines: List<Line>,
    albumArtUrl: String?,
    canvasVideoUrl: String?,
    showAlbumArt: Boolean,
    lyricColor: Color,
    lyricSizeSp: Float,
    isPlaying: Boolean,
    isPreviousAvailable: Boolean,
    isNextAvailable: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val lyricListState = rememberLazyListState()
    val artworkMotion = rememberInfiniteTransition(label = "aodArtworkMotion")
    val artworkDrift by
        artworkMotion.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 18000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "aodArtworkDrift",
        )
    val density = LocalDensity.current
    val driftX = with(density) { 5.dp.toPx() } * artworkDrift
    val driftY = with(density) { 3.dp.toPx() } * -artworkDrift

    LaunchedEffect(lyricIndex, lyricLines.size) {
        if (lyricIndex >= 0) lyricListState.centerItem(lyricIndex)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onExit),
    ) {
        if (showAlbumArt && (albumArtUrl != null || canvasVideoUrl != null)) {
            Row(modifier = Modifier.fillMaxSize()) {
                AodLyricsList(
                    lyricListState = lyricListState,
                    lyricIndex = lyricIndex,
                    lyricLines = lyricLines,
                    lyricColor = lyricColor,
                    lyricSizeSp = lyricSizeSp,
                    modifier = Modifier.weight(0.62f),
                )
                Box(
                    modifier =
                        Modifier
                            .weight(0.38f)
                            .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val artworkShape = RoundedCornerShape(24.dp)
                    Box(
                        modifier =
                            Modifier
                                .size(220.dp)
                                .offset(x = (-20).dp)
                                .background(Color.Black, artworkShape)
                                .clip(artworkShape),
                    ) {
                        val artworkModifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // B&W filter + the slow Nothing-style drift on the same layer.
                                    colorFilter = aodGrayscaleFilter
                                    translationX = driftX
                                    translationY = driftY
                                    rotationX = artworkDrift * 1.1f
                                    rotationY = artworkDrift * 2.2f
                                    rotationZ = artworkDrift * 0.25f
                                    scaleX = 1f + artworkDrift * 0.012f
                                    scaleY = 1f + artworkDrift * 0.012f
                                    cameraDistance = 10f * density.density
                                }
                        if (canvasVideoUrl != null) {
                            AodCanvasVideo(
                                url = canvasVideoUrl,
                                modifier = artworkModifier,
                            )
                        } else {
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(LocalPlatformContext.current)
                                        .data(albumArtUrl)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .diskCacheKey(albumArtUrl)
                                        .build(),
                                contentDescription = "Album artwork",
                                contentScale = ContentScale.Crop,
                                modifier = artworkModifier,
                            )
                        }
                    }
                }
            }
        } else {
            AodLyricsList(
                lyricListState = lyricListState,
                lyricIndex = lyricIndex,
                lyricLines = lyricLines,
                lyricColor = lyricColor,
                lyricSizeSp = lyricSizeSp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AodTransportControls(
            isPlaying = isPlaying,
            isPreviousAvailable = isPreviousAvailable,
            isNextAvailable = isNextAvailable,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun AodTransportControls(
    isPlaying: Boolean,
    isPreviousAvailable: Boolean,
    isNextAvailable: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(bottom = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        AodControlButton(
            icon = SimpIcons.SkipPrevious,
            size = 44,
            enabled = isPreviousAvailable,
            onClick = onPrevious,
        )
        AodControlButton(
            icon = if (isPlaying) SimpIcons.ClassicPause else SimpIcons.ClassicPlayArrow,
            size = 62,
            emphasis = true,
            onClick = onPlayPause,
        )
        AodControlButton(
            icon = SimpIcons.SkipNext,
            size = 44,
            enabled = isNextAvailable,
            onClick = onNext,
        )
    }
}

@Composable
private fun AodControlButton(
    icon: ImageVector,
    size: Int,
    emphasis: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        color = if (emphasis) Color(0x99FFFFFF) else Color(0x33FFFFFF),
        shape = CircleShape,
        modifier =
            Modifier
                .size(size.dp)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) (if (emphasis) Color.Black else Color.White) else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size((size * 0.5f).dp),
            )
        }
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun AodCanvasVideo(url: String, modifier: Modifier) {
    val context = LocalContext.current

    // Self-contained looping player for the AOD artwork. A TextureView (not SurfaceView) is
    // required so the grayscale colorFilter on the graphicsLayer actually tints the frames.
    val exoPlayer =
        remember {
            ExoPlayer
                .Builder(context)
                .build()
                .apply { videoScalingMode = C.VIDEO_SCALING_MODE_DEFAULT }
        }
    val mediaItem = remember(url) { MediaItem.fromUri(url) }

    LaunchedEffect(mediaItem) {
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    val presentationState = rememberPresentationState(exoPlayer)

    Box(modifier = modifier.graphicsLayer { clip = true }) {
        PlayerSurface(
            player = exoPlayer,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier =
                Modifier
                    .fillMaxSize()
                    .resizeWithContentScale(
                        contentScale = ContentScale.Crop,
                        sourceSizeDp = presentationState.videoSizeDp,
                    ),
        )

        if (presentationState.coverSurface) {
            Box(Modifier.matchParentSize().background(Color.Black))
        }
    }
}

// Rich-synced lyrics store word timestamps inline (<MM:SS.mm> word <MM:SS.mm> word).
// The AOD screen only tracks line-level sync, so strip the markup for display.
private val WordTimestampRegex = Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>")

private fun aodDisplayWords(raw: String): String =
    raw.replace(WordTimestampRegex, "").trim()

@Composable
private fun AodLyricsList(
    lyricListState: LazyListState,
    lyricIndex: Int,
    lyricLines: List<Line>,
    lyricColor: Color,
    lyricSizeSp: Float,
    modifier: Modifier,
) {
    val displayIndex = if (lyricIndex < 0 && lyricLines.isNotEmpty()) 0 else lyricIndex

    LazyColumn(
        state = lyricListState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 120.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (lyricLines.isEmpty()) {
            item {
                Text(
                    text = "...",
                    color = lyricColor,
                    fontSize = lyricSizeSp.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(lyricLines.size) { index ->
                val active = index == displayIndex
                Text(
                    text = aodDisplayWords(lyricLines[index].words).ifBlank { "\u266A" },
                    color = if (active) lyricColor else lyricColor.copy(alpha = 0.42f),
                    fontSize = if (active) lyricSizeSp.sp else (lyricSizeSp - 6f).coerceAtLeast(16f).sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = if (active) (lyricSizeSp + 8f).sp else (lyricSizeSp + 1f).sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private suspend fun LazyListState.centerItem(index: Int) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
    val distance = item.offset + item.size / 2f - viewportCenter
    if (abs(distance) > 1f) {
        animateScrollBy(distance, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }
}
