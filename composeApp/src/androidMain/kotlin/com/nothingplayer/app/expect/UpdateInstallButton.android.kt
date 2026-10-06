package com.nothingplayer.app.expect

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.maxrave.domain.data.model.update.UpdateData
import kotlinx.coroutines.*
import java.io.File

@Composable
actual fun UpdateInstallButton(update: UpdateData) {
    val context = LocalContext.current
    val app = context.applicationContext
    val manager = remember { app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    val prefs = remember { app.getSharedPreferences("apk_updates", Context.MODE_PRIVATE) }
    val key = update.apkUrl.orEmpty()
    var id by remember(key) { mutableLongStateOf(prefs.getLong(key, -1)) }
    var status by remember(key) { mutableStateOf("Install") }
    var ready by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val file = remember(key) { File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "nothing-player-${update.tagName.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk") }

    fun openInstaller() {
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.FileProvider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            installing = true
            error = null
        } catch (_: Exception) { error = "Unable to open Android installer. Tap Install to retry." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.packageManager.canRequestPackageInstalls()) openInstaller()
        else error = "Allow Nothing Player to install updates, then tap Install."
    }
    fun install() {
        if (app.packageManager.canRequestPackageInstalls()) openInstaller()
        else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()))
    }

    LaunchedEffect(id) {
        if (id < 0) return@LaunchedEffect
        ready = false
        try {
            while (isActive) {
                val snapshot = withContext(Dispatchers.IO) {
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (!cursor.moveToFirst()) null else Triple(
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        )
                    }
                }
                if (snapshot == null || snapshot.first == DownloadManager.STATUS_FAILED) {
                    error = "Download failed. Tap Retry download."
                    status = "Retry download"
                    prefs.edit().remove(key).apply()
                    id = -1
                    break
                }
                if (snapshot.first == DownloadManager.STATUS_SUCCESSFUL) {
                    val valid = withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        val candidate = app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                        @Suppress("DEPRECATION")
                        val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
                        @Suppress("DEPRECATION")
                        candidate != null && candidate.packageName == app.packageName && PackageInfoCompat.getLongVersionCode(candidate) > PackageInfoCompat.getLongVersionCode(installed) &&
                            !candidate.signatures.isNullOrEmpty() && candidate.signatures?.toSet() == installed.signatures?.toSet()
                    }
                    if (!valid) {
                        error = "This APK cannot update this installation. Check the app package and signing key."
                        manager.remove(id)
                        prefs.edit().remove(key).apply()
                        id = -1
                        status = "Retry download"
                        break
                    }
                    ready = true
                    status = "Install"
                    if (!installing) install()
                    break
                }
                status = if (snapshot.third > 0) "Downloading ${(snapshot.second * 100 / snapshot.third).coerceIn(0, 100)}%" else "Downloading..."
                delay(500)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Unable to check the download. Tap Retry download."; status = "Retry download"; id = -1 }
    }

    Column {
        error?.let { Text(it) }
        TextButton(enabled = !starting && (id < 0 || ready), onClick = {
            error = null
            if (ready) install()
            else if (update.apkUrl == null) openUrl(update.releaseUrl)
            else {
                starting = true
                scope.launch {
                try {
                    val newId = withContext(Dispatchers.IO) {
                        val uri = requireNotNull(update.apkUrl).toUri()
                        require(uri.scheme == "https" && uri.host == "github.com" && uri.path.orEmpty().startsWith("/iniharith/Nothing-Player/releases/download/"))
                        val previous = prefs.getLong(key, -1)
                        if (previous >= 0) manager.remove(previous)
                        file.delete()
                        manager.enqueue(DownloadManager.Request(uri)
                            .setTitle("Nothing Player ${update.tagName}")
                            .setMimeType("application/vnd.android.package-archive")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationUri(android.net.Uri.fromFile(file))).also { prefs.edit().putLong(key, it).apply() }
                    }
                    id = newId
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Unable to start download. Please retry." }
                finally { starting = false }
            }
            }
        }) { Text(if (update.apkUrl == null) "View release" else status) }
    }
}
