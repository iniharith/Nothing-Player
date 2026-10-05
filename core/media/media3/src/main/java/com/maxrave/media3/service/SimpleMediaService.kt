package com.maxrave.media3.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.utils.CoilBitmapLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

@UnstableApi
internal class SimpleMediaService : MediaLibraryService(), KoinComponent {
    private val player: Player by inject(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject()
    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject()
    private val simpleMediaServiceHandler: MediaPlayerHandler by inject()
    private val lyricsSettings: com.maxrave.domain.manager.DataStoreManager by inject()
    private val lyricsRepository: com.maxrave.domain.repository.LyricsCanvasRepository by inject()
    private lateinit var carLyricsPlayer: CarLyricsPlayer
    private var mediaSession: MediaLibrarySession? = null
    private val binder = MusicBinder()
    private var preparingPlayback = false
    private val preparationHandler = Handler(Looper.getMainLooper())
    private val preparationTimeout = Runnable {
        if (preparingPlayback) {
            stopPlayback()
            mediaSession?.release()
            mediaSession = null
        }
    }

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(context: Context, activity: Class<out Activity>) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(context, 0, Intent(context, activity), PendingIntent.FLAG_IMMUTABLE),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = super.onBind(intent) ?: binder

    override fun onCreate() {
        super.onCreate()
        // Media3 owns the foreground lifecycle. A paused player must not keep an
        // ongoing service alive or be promoted by a second notification manager.
        setForegroundServiceTimeoutMs(0)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply { setSmallIcon(R.drawable.mono) },
        )
        getSystemService<NotificationManager>()?.cancel(2026)
        carLyricsPlayer = CarLyricsPlayer(player, lyricsSettings, lyricsRepository)
        mediaSession =
            MediaLibrarySession.Builder(this, carLyricsPlayer, simpleMediaSessionCallback)
                .setId(javaClass.name)
                .setBitmapLoader(coilBitmapLoader)
                .build()
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.onCarConnectionChanged = carLyricsPlayer::setCarConnected
        // Register without a controller bound to our own service. That self-binding
        // prevented destruction even after the activity disconnected.
        mediaSession?.let(::addSession)
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.prepareForPlayback = ::prepareForegroundPlayback
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.cancelPlaybackPreparation = {
            if (preparingPlayback) stopPlayback()
        }
        simpleMediaServiceHandler.onUpdateNotification = { buttons ->
            mediaSession?.setMediaButtonPreferences(buttons.map { it.toCommandButton(this) })
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    private fun prepareForegroundPlayback(): Boolean {
        if (preparingPlayback || isPlaybackOngoing) return true
        return try {
            getSystemService<NotificationManager>()?.createNotificationChannel(
                NotificationChannel(
                    MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            // A real car/media-button resume needs foreground status before the
            // custom adapter can request audio focus on Android 15 and later.
            val notification =
                NotificationCompat.Builder(this, MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.mono)
                    .setContentTitle(player.mediaMetadata.title ?: getString(R.string.notification_channel_name))
                    .setContentText(getString(R.string.preparing_playback))
                    .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                    .setSilent(true)
                    .setOngoing(true)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(MEDIA_NOTIFICATION.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(MEDIA_NOTIFICATION.NOTIFICATION_ID, notification)
            }
            preparingPlayback = true
            preparationHandler.postDelayed(preparationTimeout, 30_000L)
            true
        } catch (error: IllegalStateException) {
            Logger.w("Service", "Playback foreground start denied: ${error.message}")
            false
        } catch (error: SecurityException) {
            Logger.w("Service", "Playback foreground permission denied: ${error.message}")
            false
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // The adapter has not yet swapped in its buffering player. Keep the brief
        // foreground notification until playback intent reaches that delegate.
        if (preparingPlayback && !session.player.playWhenReady && session.player.playerError == null) return
        super.onUpdateNotification(session, startInForegroundRequired)
        if (session.player.playWhenReady && session.player.playbackState != Player.STATE_IDLE) {
            preparingPlayback = false
            preparationHandler.removeCallbacks(preparationTimeout)
        }
    }

    private fun stopPlayback() {
        preparingPlayback = false
        preparationHandler.removeCallbacks(preparationTimeout)
        simpleMediaServiceHandler.stopPlaybackSession()
        pauseAllPlayersAndStopSelf()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService<NotificationManager>()?.cancel(MEDIA_NOTIFICATION.NOTIFICATION_ID)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val playback = simpleMediaServiceHandler.player
        val wantsPlayback =
            PlaybackServicePolicy.shouldContinuePlayback(
                playback.playWhenReady,
                playback.mediaItemCount,
                playback.playbackState == Player.STATE_READY || playback.playbackState == Player.STATE_BUFFERING,
                simpleMediaServiceHandler.shouldReleaseOnTaskRemoved(),
            )
        if (!wantsPlayback) {
            stopPlayback()
            // Disconnect controllers so an idle dismissed service can actually stop.
            mediaSession?.release()
            mediaSession = null
        }
    }

    override fun onDestroy() {
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.onCarConnectionChanged = {}
        if (::carLyricsPlayer.isInitialized) carLyricsPlayer.close()
        preparationHandler.removeCallbacksAndMessages(null)
        // The handler/player are DI singletons. Cancelling their shared scope here
        // breaks the next app launch and Android Auto reconnect.
        simpleMediaServiceHandler.onUpdateNotification = {}
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.prepareForPlayback = { false }
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.cancelPlaybackPreparation = {}
        simpleMediaServiceHandler.stopPlaybackSession()
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService<NotificationManager>()?.cancel(MEDIA_NOTIFICATION.NOTIFICATION_ID)
        super.onDestroy()
        Logger.d("Service", "Playback service destroyed")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }
}
