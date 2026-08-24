package com.nothingplayer.app

import android.annotation.SuppressLint
import android.app.Application
import android.database.CursorWindow
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import androidx.work.WorkManager
import cat.ereza.customactivityoncrash.config.CaocConfig
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.logger.Logger
import com.nothingplayer.app.di.viewModelModule
import com.nothingplayer.app.glyph.GlyphManager
import com.nothingplayer.app.service.backup.AutoBackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import multiplatform.network.cmptoast.AppContext
import okhttp3.OkHttpClient
import okio.FileSystem
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.nothingplayer.crashlytics.configCrashlytics
import org.nothingplayer.lastfm.configLastfm
import java.lang.reflect.Field

class NothingPlayerApplication :
    Application(),
    KoinComponent,
    SingletonImageLoader.Factory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataStoreManager: DataStoreManager by inject()
    private val mediaPlayerHandler: MediaPlayerHandler by inject()
    private lateinit var autoBackupScheduler: AutoBackupScheduler

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        configCrashlytics(this, BuildKonfig.sentryDsn)
        configLastfm(BuildKonfig.lastfmApiKey, BuildKonfig.lastfmSecret)
        startKoin {
            androidLogger(level = Level.DEBUG)
            androidContext(this@NothingPlayerApplication)
            loadAllModules()
            loadKoinModules(viewModelModule)
        }

        // Initialize Nothing Glyph Manager (safe to call on non-Nothing devices)
        try {
            GlyphManager.initialize(this)
        } catch (e: Exception) {
            Logger.w("NothingPlayerApplication", "Glyph init skipped: ${e.message}")
        }

        // Feed playback state into the Glyph animator
        applicationScope.launch {
            combine(
                mediaPlayerHandler.controlState,
                mediaPlayerHandler.simpleMediaState,
            ) { control, media ->
                val position =
                    when (media) {
                        is SimpleMediaState.Progress -> media.progress
                        is SimpleMediaState.Buffering -> media.position
                        else -> 0L
                    }
                GlyphManager.updatePlayback(control.isPlaying, position)
            }.collect { }
        }
        // provide custom configuration
        val workConfig =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()

        // initialize WorkManager
        WorkManager.initialize(this, workConfig)

        // Initialize and start AutoBackupScheduler
        autoBackupScheduler = AutoBackupScheduler(this, dataStoreManager)
        applicationScope.launch {
            autoBackupScheduler.observeAndSchedule()
        }

        CaocConfig.Builder
            .create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) // default: CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM
            .enabled(true) // default: true
            .showErrorDetails(true) // default: true
            .showRestartButton(true) // default: true
            .errorDrawable(R.mipmap.ic_launcher_round)
            .logErrorOnRestart(false) // default: true
            .trackActivities(true) // default: false
            .minTimeBetweenCrashesMs(2000) // default: 3000 //default: bug image
            .restartActivity(MainActivity::class.java) // default: null (your app's launch activity)
            .apply()

        @SuppressLint("DiscouragedPrivateApi")
        val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
        field.isAccessible = true
        val expectSize = 100 * 1024 * 1024
        field.set(null, expectSize)

        AppContext.apply {
            set(applicationContext)
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        Logger.w("Terminate", "Checking")
    }

    // One shared OkHttp client for every image fetch. Creating an OkHttpClient inside
    // callFactory spawned fresh connection pools and executor threads per request — visible
    // stutter while flinging through artwork-heavy lists.
    private val imageOkHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient
            .Builder()
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                    ),
                )
            }.diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }.diskCache(
                DiskCache
                    .Builder()
                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build(),
                // Crossfade animates EVERY thumbnail as it loads — pure jank while scrolling.
                // Lists want instant placement; transitions belong to full-screen art only.
            ).crossfade(false)
            .build()
}