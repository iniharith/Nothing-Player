package com.maxrave.media3.service

import android.content.Context
import android.os.Build
import com.maxrave.logger.Logger
import java.io.File
import java.util.concurrent.Executors

/** Bounded local event log: never records stream URLs, cookies or account tokens. */
object PlaybackDiagnostics {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlaybackDiagnostics").apply { isDaemon = true }
    }

    @Synchronized
    fun install(context: Context) {
        if (Logger.playbackDiagnosticSink != null) return
        val app = context.applicationContext
        val file = File(app.filesDir, "playback-diagnostics.log")
        Logger.playbackDiagnosticSink = { message ->
            writer.execute {
                runCatching {
                    if (file.length() > 128_000) file.writeText(file.readText().takeLast(64_000))
                    file.appendText("${System.currentTimeMillis()} $message\n")
                }
            }
        }
        @Suppress("DEPRECATION")
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Flush synchronously on a crash; do not record exception messages that may contain URLs.
            runCatching {
                writer.submit {
                    file.appendText("${System.currentTimeMillis()} CRASH thread=${thread.name} type=${error.javaClass.name}\n")
                    generateSequence(error) { it.cause }.take(5).forEach { cause ->
                        file.appendText("type=${cause.javaClass.name}\n" + cause.stackTrace.take(30).joinToString("\n") + "\n")
                    }
                }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            previousHandler?.uncaughtException(thread, error)
        }
        Logger.playbackEvent("diagnostics-start version=$version sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
    }

    /** Called on an IO dispatcher; flush queued events before taking the snapshot. */
    fun export(context: Context): File = writer.submit<File> {
        val source = File(context.filesDir, "playback-diagnostics.log")
        val exported = File(context.cacheDir, "Nothing-Player-playback-diagnostics.txt")
        if (source.exists()) source.copyTo(exported, overwrite = true)
        else exported.writeText("No playback events recorded yet.\n")
        exported
    }.get()
}
