package com.nothingplayer.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maxrave.domain.manager.*
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun PlayerFeatureControls(mediaId: String?, isVideo: Boolean) {
    val settings: DataStoreManager = koinInject()
    val handler: MediaPlayerHandler = koinInject()
    val scope = rememberCoroutineScope()
    val videoEnabled by settings.watchVideoInsteadOfPlayingAudio.collectAsState(initial = DataStoreManager.TRUE)
    val offset by remember(mediaId) { settings.songLyricsOffset(mediaId) }.collectAsState(initial = 0)
    val timer by handler.sleepTimerState.collectAsState()
    var options by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (isVideo) TextButton(onClick = { scope.launch { settings.setWatchVideoInsteadOfPlayingAudio(videoEnabled != DataStoreManager.TRUE) } }) {
            Text(if (videoEnabled == DataStoreManager.TRUE) "Audio only" else "Watch video")
        } else Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { options = true }) { Text(if (timer.timeRemaining == -1) "Timer: end of song" else if (timer.timeRemaining > 0) "Timer: ${timer.timeRemaining} min" else "Playback options") }
            DropdownMenu(options, { options = false }) {
                DropdownMenuItem(text = { Text("Lyrics timing") }, onClick = { options = false; timing = true })
                DropdownMenuItem(text = { Text("Sleep timer") }, onClick = { options = false; sleep = true })
            }
        }
    }
    if (timing && mediaId != null) AlertDialog(onDismissRequest = { timing = false }, title = { Text("Lyrics timing") }, text = {
        Column {
            Text("Saved for this song. Negative shows lyrics earlier; positive shows them later.")
            Text("${offset} ms", Modifier.padding(vertical = 12.dp))
            Row {
                TextButton(onClick = { scope.launch { settings.setSongLyricsOffset(mediaId, offset - 100) } }) { Text("Earlier −100 ms") }
                TextButton(onClick = { scope.launch { settings.setSongLyricsOffset(mediaId, offset + 100) } }) { Text("Later +100 ms") }
            }
            TextButton(onClick = { scope.launch { settings.setSongLyricsOffset(mediaId, 0) } }) { Text("Reset to 0") }
        }
    }, confirmButton = { TextButton(onClick = { timing = false }) { Text("Done") } })
    if (sleep) AlertDialog(onDismissRequest = { sleep = false }, title = { Text("Sleep timer") }, text = {
        Column {
            listOf(15, 30, 45, 60, Int.MAX_VALUE).forEach { minutes ->
                TextButton(onClick = { handler.sleepStart(minutes); sleep = false }) { Text(if (minutes == Int.MAX_VALUE) "End of current song" else "$minutes minutes") }
            }
            TextButton(onClick = { handler.sleepStop(); sleep = false }) { Text("Cancel timer") }
        }
    }, confirmButton = { TextButton(onClick = { sleep = false }) { Text("Close") } })
}

@Composable
fun VideoPreferenceSettings() {
    val settings: DataStoreManager = koinInject()
    val scope = rememberCoroutineScope()
    val wifi by settings.getString(VIDEO_WIFI_QUALITY).collectAsState(initial = null)
    val mobile by settings.getString(VIDEO_MOBILE_QUALITY).collectAsState(initial = null)
    val queue by settings.saveRecentSongAndQueue.collectAsState(initial = DataStoreManager.TRUE)
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Playback and video preferences", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { editing = VIDEO_WIFI_QUALITY }) { Text("Wi-Fi quality: ${wifi ?: "Auto"}") }
        TextButton(onClick = { editing = VIDEO_MOBILE_QUALITY }) { Text("Mobile data quality: ${mobile ?: "Auto"}") }
        Text("Auto selects up to 1080p on unmetered connections and 360p on mobile or metered connections.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Remember playback queue", Modifier.weight(1f).padding(top = 14.dp))
            Switch(checked = queue == DataStoreManager.TRUE, onCheckedChange = { enabled -> scope.launch { settings.setSaveRecentSongAndQueue(enabled) } })
        }
        TextButton(onClick = { confirmation = VIDEO_HISTORY }) { Text("Clear video watch history") }
        TextButton(onClick = { confirmation = VIDEO_FEED_FILTERS }) { Text("Reset hidden videos and channels") }
    }
    val key = editing
    if (key != null) AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (key == VIDEO_WIFI_QUALITY) "Wi-Fi video quality" else "Mobile data video quality") }, text = {
        Column {
            listOf("Auto", "1080p", "720p", "360p").forEach { value ->
                TextButton(onClick = { scope.launch { settings.putString(key, value); settings.putString(VIDEO_QUALITY_OVERRIDE, "") }; editing = null }) { Text(value) }
            }
        }
    }, confirmButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    val clear = confirmation
    if (clear != null) AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (clear == VIDEO_HISTORY) "Clear watch history?" else "Reset recommendation filters?") }, text = { Text(if (clear == VIDEO_HISTORY) "Saved video positions will also be removed." else "Hidden videos and channels will be allowed in this app again.") }, confirmButton = {
        TextButton(onClick = { scope.launch { settings.putString(clear, if (clear == VIDEO_HISTORY) "[]" else "{}") }; confirmation = null }) { Text("Confirm") }
    }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
}

/** Show the saved queue without preparing streams until the user requests it. */
@Composable
fun SavedQueueResumeCard() {
    val settings: DataStoreManager = koinInject()
    val handler: MediaPlayerHandler = koinInject()
    val enabled by settings.saveRecentSongAndQueue.collectAsState(initial = DataStoreManager.TRUE)
    val savedId by settings.recentMediaId.collectAsState(initial = "")
    val savedPosition by settings.recentPosition.collectAsState(initial = "0")
    val playlist by settings.playlistFromSaved.collectAsState(initial = "")
    val current by handler.nowPlayingState.collectAsState()
    if (enabled == DataStoreManager.TRUE && savedId.isNotBlank() && current.mediaItem.mediaId.isBlank()) {
        OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Continue your last queue", style = MaterialTheme.typography.titleMedium)
                if (playlist.isNotBlank()) Text(playlist, style = MaterialTheme.typography.bodyMedium)
                val seconds = (savedPosition.toLongOrNull() ?: 0).coerceAtLeast(0) / 1000
                Text("Saved at ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { handler.mayBeRestoreQueue() }) { Text("Restore queue") }
            }
        }
    }
}
