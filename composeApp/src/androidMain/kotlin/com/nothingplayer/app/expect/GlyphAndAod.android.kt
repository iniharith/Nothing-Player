package com.nothingplayer.app.expect

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import org.koin.mp.KoinPlatform.getKoin

private const val LYRICS_ONLY_ACTIVITY_CLASS = "com.nothingplayer.app.lyrics.LyricsOnlyActivity"

actual fun launchAodLyrics() {
    val context: AppCompatActivity = getKoin().get()
    try {
        val intent = Intent()
        intent.setClassName(context, LYRICS_ONLY_ACTIVITY_CLASS)
        context.startActivity(intent)
    } catch (_: Exception) {
        // Activity not registered; ignore on desktop-less builds
    }
}

actual fun isGlyphDeviceSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        Build.MANUFACTURER.equals("Nothing", ignoreCase = true)
