package com.nothingplayer.app.expect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

@Composable
internal fun DesktopYouTubeLogin(
    state: MutableState<WebViewState>,
    aboveContent: @Composable BoxScope.() -> Unit,
    onPageFinished: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DesktopBrowserLogin?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { state.value = WebViewState.Finished }
    DisposableEffect(session) {
        val owned = session
        onDispose { owned?.let { thread(isDaemon = true, name = "ClosePlayerSignIn") { it.close() } } }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 540.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sign in to YouTube Music", style = MaterialTheme.typography.headlineSmall)
            Text("Open the sign-in window and sign in with Google. Once YouTube Music shows your account, close that separate browser window, then choose Finish sign-in here.",
                textAlign = TextAlign.Center)
            Text("Chrome is used when installed. This separate sign-in window leaves your usual browser profiles unchanged.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            if (busy) CircularProgressIndicator(Modifier.size(28.dp))
            Button(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        session?.let { withContext(Dispatchers.IO) { it.close() } }
                        session = withContext(Dispatchers.IO) { DesktopBrowserLogin.open() }
                    } catch (_: Exception) {
                        error = "Could not open the sign-in window. Make sure Microsoft Edge or Google Chrome is installed, then try again."
                    } finally { busy = false }
                }
            }) { Text(if (session == null) "Open sign-in window" else "Restart sign-in") }
            Button(enabled = !busy && session != null, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val active = checkNotNull(session)
                        val cookies = withContext(Dispatchers.IO) { active.readCookies() }
                        DesktopCapturedCookies.value = cookies
                        // Keep the window available if account validation fails.
                        // Navigation after a successful import disposes and closes it.
                        onPageFinished("https://music.youtube.com/")
                    } catch (_: DesktopSignInWindowStillOpen) {
                        error = "Close the separate sign-in browser window after signing in, then choose Finish sign-in."
                    } catch (_: Exception) {
                        error = "Could not import a signed-in session. Choose Restart sign-in, sign in to YouTube Music, close that browser window, and try again."
                    } finally { busy = false }
                }
            }) { Text("Finish sign-in") }
        }
        aboveContent()
    }
}
