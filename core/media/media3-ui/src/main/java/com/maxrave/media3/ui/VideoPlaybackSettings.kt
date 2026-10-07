package com.maxrave.media3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.maxrave.common.Config
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.*
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/** Uses the application's current player; never starts a second audio/video player. */
@Composable
fun VideoPlaybackSettings(modifier: Modifier = Modifier, alignRight: Boolean = true) {
    val player: Player = koinInject(named(Config.MAIN_PLAYER))
    val settings: DataStoreManager = koinInject()
    val repository: LyricsCanvasRepository = koinInject()
    val scope = rememberCoroutineScope()
    val qualityOverride by settings.getString(VIDEO_QUALITY_OVERRIDE).collectAsState(initial = null)
    val videoEnabled by settings.watchVideoInsteadOfPlayingAudio.collectAsState(initial = DataStoreManager.TRUE)
    var mediaId by remember { mutableStateOf(player.currentMediaItem?.mediaId) }
    var actualHeight by remember { mutableIntStateOf(player.videoSize.height) }
    val quality = qualityOverride?.takeIf { it.substringBefore(':') == mediaId.orEmpty().removePrefix("Video") }?.substringAfter(':') ?: "Auto"
    var qualityMenu by remember { mutableStateOf(false) }
    var captionsMenu by remember { mutableStateOf(false) }
    val languagePreference by settings.getString(CAPTION_LANGUAGE).collectAsState(initial = null)
    val captionSizePreference by settings.getString(CAPTION_SIZE).collectAsState(initial = null)
    val language = languagePreference ?: "off"
    val captionScale = captionSizePreference?.toFloatOrNull()?.coerceIn(0.8f, 1.6f) ?: 1f
    var captions by remember { mutableStateOf<Lyrics?>(null) }
    var status by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { mediaId = item?.mediaId }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { actualHeight = size.height }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(mediaId, language) {
        captions = null
        line = ""
        status = ""
        if (language == "off") return@LaunchedEffect
        val id = mediaId?.removePrefix("Video") ?: return@LaunchedEffect
        status = "Loading captions…"
        try {
            val result = withTimeoutOrNull(20_000) { repository.getYouTubeCaption(if (language == "original") "en" else language, id).firstOrNull() }
            captions = if (language == "original") result?.data?.first else result?.data?.second
            status = if (captions?.lines.isNullOrEmpty()) "Captions unavailable for this video" else ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Unable to load captions. Try again." }
    }
    LaunchedEffect(captions, mediaId) {
        val timed = captions?.lines.orEmpty().mapNotNull { item ->
            item.startTimeMs.toLongOrNull()?.let { Triple(it, item.endTimeMs.toLongOrNull(), item.words) }
        }.sortedBy { it.first }
        while (true) {
            val position = player.currentPosition
            line = captionTextAt(timed, position)
            delay(200)
        }
    }
    Box(modifier) {
        Row(Modifier.align(if (alignRight) Alignment.TopEnd else Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))) {
            TextButton(onClick = { scope.launch { settings.setWatchVideoInsteadOfPlayingAudio(videoEnabled != DataStoreManager.TRUE) } }) {
                Text(if (videoEnabled == DataStoreManager.TRUE) "Audio only" else "Watch video", color = Color.White)
            }
            Box {
                TextButton(onClick = { qualityMenu = true }) { Text(if (actualHeight > 0) "${actualHeight}p ⚙" else "$quality ⚙", color = Color.White) }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                    Text("Video quality", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    listOf("Auto", "1080p", "720p", "360p").forEach { resolution ->
                        DropdownMenuItem(text = { Text(if (resolution == quality) "$resolution ✓" else resolution) }, onClick = {
                            qualityMenu = false
                            scope.launch { settings.putString(VIDEO_QUALITY_OVERRIDE, if (resolution == "Auto") "" else "${mediaId.orEmpty().removePrefix("Video")}:$resolution") }
                        })
                    }
                    Text("Uses the closest available stream", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                TextButton(onClick = { captionsMenu = true }) { Text(if (language == "off") "CC" else "CC ✓", color = Color.White) }
                DropdownMenu(expanded = captionsMenu, onDismissRequest = { captionsMenu = false }) {
                    listOf("off" to "Off", "original" to "Original", "en" to "English", "ms" to "Malay", "id" to "Indonesian").forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(if (language == code) "$label ✓" else label) }, onClick = { captionsMenu = false; scope.launch { settings.putString(CAPTION_LANGUAGE, code) } })
                    }
                    HorizontalDivider()
                    Text("Caption size", Modifier.padding(12.dp))
                    listOf("0.8" to "Small", "1.0" to "Medium", "1.4" to "Large").forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { captionsMenu = false; scope.launch { settings.putString(CAPTION_SIZE, value) } })
                    }
                }
            }
        }
        val text = line.ifBlank { status }
        if (language != "off" && text.isNotBlank()) Text(text, color = Color.White, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * captionScale),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(6.dp))
    }
}

/** Half-open intervals prevent a previous caption lingering at the next cue or after a seek. */
internal fun captionTextAt(timed: List<Triple<Long, Long?, String>>, position: Long): String {
    val index = timed.indexOfLast { it.first <= position }
    val item = timed.getOrNull(index) ?: return ""
    val end = item.second?.takeIf { it > item.first } ?: timed.getOrNull(index + 1)?.first
    return if (end == null || position < end) item.third else ""
}
