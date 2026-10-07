package com.maxrave.media3.di

import android.app.Activity
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.maxrave.common.Config.CANVAS_CACHE
import com.maxrave.common.Config.DOWNLOAD_CACHE
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.common.Config.PLAYER_CACHE
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.plusSeconds
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.cast.CastHandoffManager
import com.maxrave.media3.cast.CastStreamResolver
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
import com.maxrave.media3.exoplayer.StreamUrlCache
import com.maxrave.media3.extension.isFullyCached
import com.maxrave.media3.repository.CacheRepositoryImpl
import com.maxrave.media3.service.SimpleMediaService
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.service.download.DownloadUtils
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.nothingplayer.cast.initCast
import org.nothingplayer.cast.wrapWithCastPlayer
import java.net.Proxy
import kotlin.time.Duration.Companion.seconds

/**
 * Required repository first initialization
 */
@UnstableApi
private val mediaServiceModule =
    module {
        // Service
        // CoroutineScope for service
        single<CoroutineScope>(
            createdAtStart = true,
            qualifier = named(SERVICE_SCOPE),
        ) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        // Cache
        single<DatabaseProvider>(
            createdAtStart = true,
        ) {
            provideDatabaseProvider(androidContext())
        }
        // Player Cache
        single<SimpleCache>(qualifier = named(PLAYER_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "exoplayer",
                cacheSize = runBlocking { get<DataStoreManager>().maxSongCacheSize.first() },
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Download Cache
        single<SimpleCache>(qualifier = named(DOWNLOAD_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "download",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // Spotify Canvas Cache
        single<SimpleCache>(qualifier = named(CANVAS_CACHE), createdAtStart = true) {
            provideSimpleCache(
                context = androidContext(),
                cacheName = "spotifyCanvas",
                cacheSize = -1,
                databaseProvider = get<DatabaseProvider>(),
            )
        }
        // DownloadUtils
        single<DownloadHandler>(createdAtStart = true) {
            DownloadUtils(
                context = androidContext(),
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                dataStoreManager = get(),
                databaseProvider = get(),
                streamRepository = get(),
                songRepository = get(),
            )
        }

        // AudioAttributes
        single<AudioAttributes>(createdAtStart = true) {
            provideAudioAttributes()
        }

        single<MergingMediaSourceFactory>(createdAtStart = true) {
            provideMergingMediaSource(
                androidContext(),
                get(named(DOWNLOAD_CACHE)),
                get(named(PLAYER_CACHE)),
                get(),
                get(named(SERVICE_SCOPE)),
                get(),
                get(),
            )
        }

        single { StreamUrlCache() }

        single<DefaultRenderersFactory>(createdAtStart = true) {
            provideRendererFactory(androidContext())
        }

        // Player exposed for MediaSession + UI (video rendering via PlayerView/PlayerSurface).
        // The adapter's ForwardingPlayer (delegating to the active ExoPlayer) is wrapped with
        // Cast support in the full build; org.nothingplayer.cast no-ops back to the same instance
        // in the FOSS build, so this stays the stable session-level player either way.
        single<Player>(qualifier = named(MAIN_PLAYER)) {
            val adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter
            initCast(androidContext())
            wrapWithCastPlayer(androidContext(), adapter.forwardingPlayer)
        }

        // CoilBitmapLoader
        single<CoilBitmapLoader>(createdAtStart = true) {
            provideCoilBitmapLoader(androidContext(), get(named(SERVICE_SCOPE)))
        }

        single<MediaPlayerInterface>(createdAtStart = true) {
            CrossfadeExoPlayerAdapter(
                context = androidContext(),
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                mediaSourceFactory = get(),
                audioAttributes = get(),
                streamRepository = get(),
                streamUrlCache = get(),
            )
        }

        // Local ↔ Cast receiver handoff. No-op when wrapWithCastPlayer returned the plain
        // ForwardingPlayer (FOSS build or no GMS on the device).
        single<CastHandoffManager>(createdAtStart = true) {
            CastHandoffManager(
                adapter = get<MediaPlayerInterface>() as CrossfadeExoPlayerAdapter,
                sessionPlayer = get(qualifier = named(MAIN_PLAYER)),
                resolver = CastStreamResolver(get(), get()),
                coroutineScope = get(qualifier = named(SERVICE_SCOPE)),
            ).also { it.start() }
        }

        // MediaSession Callback for main player
        single<MediaLibrarySession.Callback>(createdAtStart = true) {
            SimpleMediaSessionCallback(
                androidApplication(),
                get<CoroutineScope>(named(SERVICE_SCOPE)),
                get<MediaPlayerHandler>(),
                get<SearchRepository>(),
                get<SongRepository>(),
                get<LocalPlaylistRepository>(),
                get<PlaylistRepository>(),
                get<HomeRepository>(),
                get<StreamRepository>(),
                get<DataStoreManager>(),
            )
        }

        single<CacheRepository>(createdAtStart = true) {
            CacheRepositoryImpl(
                playerCache = get(named(PLAYER_CACHE)),
                downloadCache = get(named(DOWNLOAD_CACHE)),
                canvasCache = get(named(CANVAS_CACHE)),
            )
        }
    }

/**
 * Conservative TTL for stream URLs resolved through [StreamRepository.getStream], which only
 * returns the raw URL string without its real expiry. YouTube playback URLs normally live ~6h;
 * 30 minutes is well inside that while still covering a full listening session per track.
 */
private const val RESOLVED_STREAM_TTL_SECONDS = 1800L

private fun provideResolvingDataSourceFactory(
    cacheDataSourceFactory: CacheDataSource.Factory,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    dataStoreManager: DataStoreManager,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    resolvedStreams: StreamUrlCache,
): DataSource.Factory {
    val chunkLength = 10 * 512 * 1024L
    // In-memory stream resolution cache. ResolvingDataSource re-opens the DataSpec at every
    // chunk boundary, and each open used to hit the Room DB and — when a cached URL existed —
    // fire an is403 HTTP probe, all blocking on ExoPlayer's loading thread. A tiny per-process
    // map keyed by videoId turns chunk 2..N into pure memory lookups. Entries expire with the
    // upstream URL; a fully broken entry only costs one playback error before the normal
    // resolve path takes over again.
    return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
        val cacheKey = dataSpec.key ?: throw java.io.IOException("No media id")
        val mediaId = cacheKey.substringBefore(":quality=")
        Logger.w("Stream", mediaId)
        Logger.w("Stream", mediaId.startsWith(MERGING_DATA_TYPE.VIDEO).toString())
        if (downloadCache.isFullyCached(cacheKey, dataSpec.position)) {
            // Only on the first chunk: the subrange below makes the resolver run once per
            // chunk, and updateFormat is a fire-and-forget youTube.player() call with no
            // in-flight dedup, so leaving it ungated would fan out one request per 5 MiB.
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    streamRepository.updateFormat(
                        if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                            mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                        } else {
                            mediaId
                        },
                    )
                }
            }
            Logger.w("Stream", "Downloaded $mediaId")
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        if (playerCache.isFullyCached(cacheKey, dataSpec.position)) {
            // See the note above: once per track, not once per chunk.
            if (dataSpec.position == 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    streamRepository.updateFormat(
                        if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                            mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                        } else {
                            mediaId
                        },
                    )
                }
            }
            Logger.w("Stream", "Cached $mediaId")
            // Every byte is on disk right now, so CacheDataSource can serve this chunk
            // without ever reaching upstream, and the bare media id is safe as the URI.
            //
            // It is only safe for ONE chunk though. A bare id has no scheme, so
            // DefaultDataSource routes it to FileDataSource, not to OkHttp — the failure
            // is FileNotFoundException (ERROR_CODE_IO_FILE_NOT_FOUND), which Media3 lists
            // as non-retriable and which CrossfadeExoPlayerAdapter does not recover from
            // either. Meanwhile CacheDataSource.read() walks span to span inside a single
            // open() without consulting this resolver again, so an unbounded DataSpec
            // would stake the whole remaining track on a snapshot taken here: one LRU
            // eviction (precache and downloads write to playerCache concurrently) or one
            // "clear cache" tap mid-song and playback dies with no way back.
            // Capping to chunkLength forces a re-check at every chunk boundary, so a
            // cache that shrinks under us falls back to resolving a real URL.
            return@Factory dataSpec.subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        resolvedStreams[cacheKey]?.let { cached ->
            Logger.d("Stream", "Resolved $mediaId from memory cache")
            return@Factory dataSpec.withUri(cached.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
        }
        var dataSpecReturn: DataSpec = dataSpec
        var resolved = false
        runBlocking(Dispatchers.IO) {
            if (mediaId.contains(MERGING_DATA_TYPE.VIDEO)) {
                val id = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                (if (cacheKey.contains(":quality=")) null else streamRepository.getNewFormat(mediaId).firstOrNull())?.let {
                    val videoUrl = it.videoUrl
                    if (videoUrl != null && it.expiredTime > now()) {
                        Logger.w("Stream", "Video from format")
                        dataSpecReturn = dataSpec.withUri(videoUrl.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                        resolvedStreams.put(cacheKey, videoUrl, it.expiredTime)
                        resolved = true
                        return@runBlocking
                    }
                }
                streamRepository
                    .getStream(
                        dataStoreManager,
                        id,
                        isDownloading = false,
                        isVideo = true,
                        videoQualityOverride = cacheKey.substringAfter(":quality=", "").substringBefore(":").takeIf { it.isNotBlank() },
                    ).firstOrNull()
                    ?.let {
                        Logger.w("Stream", "Video")
                        dataSpecReturn = dataSpec.withUri(it.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                        resolvedStreams.put(cacheKey, it, now().plusSeconds(RESOLVED_STREAM_TTL_SECONDS))
                        resolved = true
                    }
            } else {
                streamRepository.getNewFormat(mediaId).firstOrNull()?.let {
                    val audioUrl = it.audioUrl
                    if (audioUrl != null && it.expiredTime > now()) {
                        Logger.w("Stream", "Audio from format")
                        // The media open verifies the URL. A separate HTTP probe costs a
                        // round trip on every track; source-error recovery invalidates it.
                        dataSpecReturn = dataSpec.withUri(audioUrl.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                        resolvedStreams.put(cacheKey, audioUrl, it.expiredTime)
                        resolved = true
                        return@runBlocking
                    }
                }
                streamRepository
                    .getStream(
                        dataStoreManager,
                        mediaId,
                        isDownloading = false,
                        isVideo = false,
                    ).firstOrNull()
                    ?.let {
                        Logger.w("Stream", "Audio")
                        dataSpecReturn = dataSpec.withUri(it.toUri()).subrange(dataSpec.uriPositionOffset, chunkLength)
                        resolvedStreams.put(cacheKey, it, now().plusSeconds(RESOLVED_STREAM_TTL_SECONDS))
                        resolved = true
                    }
            }
        }
        if (!resolved) {
            Logger.e("Stream", "Failed to resolve stream URL for $mediaId")
            throw java.io.IOException("Failed to resolve stream URL for $mediaId")
        }
        return@Factory dataSpecReturn
    }
}

@UnstableApi
private fun provideExtractorFactory(): ExtractorsFactory =
    ExtractorsFactory {
        arrayOf(
            FlacExtractor(
                FlacExtractor.FLAG_DISABLE_ID3_METADATA,
            ),
            MatroskaExtractor(
                DefaultSubtitleParserFactory(),
            ),
            FragmentedMp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
            Mp4Extractor(
                DefaultSubtitleParserFactory(),
            ),
        )
    }

@UnstableApi
private fun provideMediaSourceFactory(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    dataStoreManager: DataStoreManager,
    coroutineScope: CoroutineScope,
    streamUrlCache: StreamUrlCache,
): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        provideResolvingDataSourceFactory(
            provideCacheDataSource(
                downloadCache,
                playerCache,
                context,
                dataStoreManager.getJVMProxy()?.let {
                    Proxy(
                        when (it.type) {
                            DataStoreManager.ProxyType.PROXY_TYPE_HTTP -> Proxy.Type.HTTP
                            DataStoreManager.ProxyType.PROXY_TYPE_SOCKS -> Proxy.Type.SOCKS
                        },
                        java.net.InetSocketAddress(it.host, it.port),
                    )
                },
            ),
            downloadCache,
            playerCache,
            dataStoreManager,
            streamRepository,
            coroutineScope,
            streamUrlCache,
        ),
        provideExtractorFactory(),
    )

@OptIn(UnstableApi::class)
private fun provideMergingMediaSource(
    context: Context,
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    streamRepository: StreamRepository,
    coroutineScope: CoroutineScope,
    dataStoreManager: DataStoreManager,
    streamUrlCache: StreamUrlCache,
): MergingMediaSourceFactory =
    MergingMediaSourceFactory(
        provideMediaSourceFactory(
            context,
            downloadCache,
            playerCache,
            streamRepository,
            dataStoreManager,
            coroutineScope,
            streamUrlCache,
        ),
        dataStoreManager,
        context,
    )

@UnstableApi
private fun provideRendererFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(
                            2_000_000,
                            (20_000 / 2_000_000).toFloat(),
                            2_000_000,
                            0,
                            256,
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
    }

@UnstableApi
private fun provideCacheDataSource(
    downloadCache: SimpleCache,
    playerCache: SimpleCache,
    context: Context,
    proxy: Proxy? = null,
): CacheDataSource.Factory =
    CacheDataSource
        .Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    DefaultDataSource
                        .Factory(
                            context,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .connectTimeout(30.seconds)
                                    .readTimeout(30.seconds)
                                    .proxy(
                                        proxy,
                                    ).addInterceptor(
                                        HttpLoggingInterceptor()
                                            .apply {
                                                level = HttpLoggingInterceptor.Level.HEADERS
                                            },
                                    ).build(),
                            ),
                        ),
                ),
        ).setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

@UnstableApi
private fun provideAudioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

@UnstableApi
private fun provideDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)

@UnstableApi
private fun provideSimpleCache(
    context: Context,
    cacheName: String,
    cacheSize: Int = -1,
    databaseProvider: DatabaseProvider,
) = SimpleCache(
    context.filesDir.resolve(cacheName),
    when (cacheSize) {
        -1 -> NoOpCacheEvictor()
        else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
    },
    databaseProvider,
)

@UnstableApi
private fun provideCoilBitmapLoader(
    context: Context,
    coroutineScope: CoroutineScope,
): CoilBitmapLoader = CoilBitmapLoader(context, coroutineScope)

@OptIn(UnstableApi::class)
fun loadMediaService() {
    loadKoinModules(mediaServiceModule)
}

@OptIn(UnstableApi::class)
fun startService(
    context: Context,
    serviceConnection: ServiceConnection,
) {
    val intent = Intent(context, SimpleMediaService::class.java)
    // Binding keeps an idle session alive only while an app/controller needs it.
    // MediaLibraryService starts its foreground lifetime when playback actually starts.
    if (context.bindService(intent, serviceConnection, BIND_AUTO_CREATE)) {
        Logger.d("Service", "Service bound")
    } else {
        Logger.e("Service", "Could not bind media service")
    }
}

@OptIn(UnstableApi::class)
fun stopService(context: Context) {
    context.stopService(Intent(context, SimpleMediaService::class.java))
}

@OptIn(UnstableApi::class)
fun setServiceActivitySession(
    context: Context,
    cls: Class<out Activity>,
    musicService: IBinder?,
) {
    (musicService as? SimpleMediaService.MusicBinder)?.setActivitySession(context, cls)
}

@OptIn(UnstableApi::class)
fun retainServiceForPlayback(musicService: IBinder?) {
    (musicService as? SimpleMediaService.MusicBinder)?.retainForPlayback()
}
