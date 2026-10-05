package com.maxrave.media3.service.callback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.LOCAL_PLAYLIST_ID_SAVED_QUEUE
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AndroidAuto"

/** Max home-shelf continuation pages fetched while browsing [HOME] children. */
private const val HOME_MAX_CONTINUATIONS = 5

@UnstableApi
internal class SimpleMediaSessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val homeRepository: HomeRepository,
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) : MediaLibrarySession.Callback {
    /** The owning service promotes actual resume requests before app-level audio focus. */
    var prepareForPlayback: () -> Boolean = { true }
    var cancelPlaybackPreparation: () -> Unit = {}

    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    private val searchTempList = mutableListOf<Track>()
    private val listHomeItem = mutableListOf<HomeItem>()
    private val carResumeGate = CarPlaybackResumptionGate<MediaSession.ControllerInfo>()
    private var carResumeJob: Job? = null
    private var carPlayer: Player? = null
    private var carSession: MediaSession? = null
    private var pendingRadioTrack: String? = null
    private val carPlayerListener =
        object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    carResumeGate.onUserPause()
                    carResumeJob?.cancel()
                    cancelPlaybackPreparation()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                val radioTrack = pendingRadioTrack ?: return
                if (carPlayer?.currentMediaItem?.mediaId == radioTrack) {
                    pendingRadioTrack = null
                    mediaPlayerHandler.getRelated(radioTrack)
                }
            }
        }

    var onCarConnectionChanged: (Boolean) -> Unit = {}

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (!isCarController(session, controller)) return
        if (carSession !== session) {
            carResumeJob?.cancel()
            carPlayer?.removeListener(carPlayerListener)
            carResumeGate.reset()
            carSession = session
        }
        if (!carResumeGate.connect(controller)) return
        onCarConnectionChanged(true)
        carPlayer = session.player.also { it.addListener(carPlayerListener) }
        carResumeJob?.cancel()
        carResumeJob =
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    val savedQueue = if (mediaPlayerHandler.player.currentMediaItem == null) readPlaybackResumptionQueue() else null
                    // A phone/head-unit pause or a disconnect while Room/DataStore were loading
                    // must win over the automatic resume request.
                    val alreadyHasPlayback =
                        mediaPlayerHandler.player.currentMediaItem != null && mediaPlayerHandler.player.playWhenReady
                    if (!carResumeGate.canAutomaticallyResume() || alreadyHasPlayback) return@launch
                    if (mediaPlayerHandler.player.currentMediaItem == null && savedQueue == null) return@launch
                    if (!prepareForPlayback()) return@launch
                    if (mediaPlayerHandler.player.currentMediaItem == null) {
                        if (savedQueue == null) return@launch
                        setRestoredQueue(savedQueue)
                        session.player.setMediaItems(
                            savedQueue.tracks.map { it.toMediaItem() },
                            savedQueue.startIndex,
                            savedQueue.positionMs,
                        )
                    }
                    if (session.player.playbackState == Player.STATE_IDLE || session.player.playbackState == Player.STATE_ENDED) {
                        session.player.prepare()
                    }
                    session.player.play()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.e(TAG, "Car playback resumption failed: ${error.message}")
                }
            }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (carSession !== session || !isCarController(session, controller) || !carResumeGate.disconnect(controller)) return
        onCarConnectionChanged(false)
        carResumeJob?.cancel()
        carResumeJob = null
        carPlayer?.removeListener(carPlayerListener)
        carPlayer = null
        carSession = null
        cancelPlaybackPreparation()
    }

    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        val playRequested = mediaPlayerHandler.player.playWhenReady
        if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) && playRequested) {
            if (!prepareForPlayback()) session.player.pause()
        }
        if (!playRequested &&
            (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) || playerCommands.contains(Player.COMMAND_STOP))
        ) {
            carResumeGate.onUserPause()
            carResumeJob?.cancel()
            cancelPlaybackPreparation()
        }
    }

    private fun isCarController(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        session.isAutoCompanionController(controller) ||
            (controller.uid == android.os.Process.myUid() &&
                controller.packageName == context.packageName &&
                controller.connectionHints.getBoolean(CAR_BROWSER_CONNECTION_HINT))

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // The host's explicit play request supersedes our pending connection resume.
        if (isForPlayback) carResumeJob?.cancel()
        return scope.future(Dispatchers.Main.immediate) {
            val savedQueue = readPlaybackResumptionQueue() ?: throw UnsupportedOperationException("No saved playback queue")
            if (isForPlayback) {
                check(prepareForPlayback()) { "Playback service could not enter the foreground" }
                setRestoredQueue(savedQueue)
                MediaSession.MediaItemsWithStartPosition(
                    savedQueue.tracks.map { it.toMediaItem() },
                    savedQueue.startIndex,
                    savedQueue.positionMs,
                )
            } else {
                // SystemUI may request only metadata at boot. Never load/play the player then.
                MediaSession.MediaItemsWithStartPosition(
                    listOf(savedQueue.tracks[savedQueue.startIndex].toMediaItem()),
                    0,
                    savedQueue.positionMs,
                )
            }
        }
    }

    private suspend fun readPlaybackResumptionQueue(): PlaybackResumptionQueue? =
        withContext(Dispatchers.IO) {
            if (dataStoreManager.saveRecentSongAndQueue.first() != DataStoreManager.TRUE) return@withContext null
            val mediaId = dataStoreManager.recentMediaId.first()
            if (mediaId.isBlank()) return@withContext null
            val position = dataStoreManager.recentPosition.first()
            val savedTracks = songRepository.getSavedQueue().firstOrNull()?.firstOrNull()?.listTrack.orEmpty()
            val currentTrack =
                songRepository.getSongById(mediaId).first()?.toTrack()
                    ?: savedTracks.firstOrNull { it.videoId == mediaId }
                    ?: return@withContext null
            playbackResumptionQueue(currentTrack, savedTracks, position, dataStoreManager.playlistFromSaved.first())
        }

    private fun setRestoredQueue(queue: PlaybackResumptionQueue) {
        mediaPlayerHandler.setPlaybackResumptionQueue(
            QueueData.Data(
                listTracks = queue.tracks,
                firstPlayedTrack = queue.tracks[queue.startIndex],
                playlistId = LOCAL_PLAYLIST_ID_SAVED_QUEUE,
                playlistName = queue.playlistName,
                playlistType = PlaylistType.PLAYLIST,
            ),
        )
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Logger.w(TAG, "onConnect: ${controller.packageName}")
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                // Add custom commands
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle()))
                .build()
        return MediaSession.ConnectionResult
            .AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .remove(COMMAND_GET_TIMELINE)
                    .build(),
            ).build()
    }

    @UnstableApi
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MEDIA_CUSTOM_COMMAND.LIKE -> {
                toggleLike()
            }

            MEDIA_CUSTOM_COMMAND.REPEAT -> {
                session.player.repeatMode =
                    when (session.player.repeatMode) {
                        ExoPlayer.REPEAT_MODE_OFF -> ExoPlayer.REPEAT_MODE_ONE
                        ExoPlayer.REPEAT_MODE_ONE -> ExoPlayer.REPEAT_MODE_ALL
                        ExoPlayer.REPEAT_MODE_ALL -> ExoPlayer.REPEAT_MODE_OFF
                        else -> ExoPlayer.REPEAT_MODE_OFF
                    }
            }

            MEDIA_CUSTOM_COMMAND.RADIO -> {
                toggleRadio()
            }

            MEDIA_CUSTOM_COMMAND.SHUFFLE -> {
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }

            MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN -> {
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putParcelable(MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN, session.platformToken)
                        },
                    ),
                )
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        scope.future(Dispatchers.IO) {
            val searchResult =
                searchRepository.getSearchDataSong(query).lastOrNull()?.let { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            resource.data?.let {
                                searchTempList.clear()
                                searchTempList.addAll(it.toListTrack())
                            }
                            resource.data
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }
            if (searchResult != null) {
                session.notifySearchResultChanged(browser, query, searchResult.size, params)
            }
            LibraryResult.ofVoid()
        }

    @UnstableApi
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            LibraryResult.ofItemList(
                searchTempList.map {
                    it.toMediaItemWithoutPath()
                },
                params,
            )
        }

    @UnstableApi
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            val rootExtras =
                Bundle().apply {
                    putBoolean(
                        MEDIA_SEARCH_SUPPORTED,
                        true,
                    )
                }
            val libraryParams =
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(rootExtras)
                    .build()
            return@future LibraryResult.ofItemList(
                when (parentId) {
                    ROOT -> {
                        listOf(
                            browsableMediaItem(
                                HOME,
                                context.getString(R.string.home),
                                context.getString(R.string.available_online),
                                drawableUri(R.drawable.home_android_auto),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            browsableMediaItem(
                                SONG,
                                context.getString(R.string.songs),
                                null,
                                drawableUri(R.drawable.baseline_album_24),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                FAVORITE,
                                context.getString(R.string.favorites),
                                null,
                                drawableUri(R.drawable.baseline_favorite_24),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                DOWNLOADED,
                                context.getString(R.string.downloaded),
                                null,
                                drawableUri(R.drawable.baseline_downloaded),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                PLAYLIST,
                                context.getString(R.string.playlists),
                                null,
                                drawableUri(R.drawable.baseline_playlist_add_24),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                        )
                    }

                    SONG -> {
                        songRepository
                            .getAllSongs(1000)
                            .last()
                            .sortedBy { it.inLibrary }
                            .map { it.toMediaItem(parentId) }
                    }

                    FAVORITE -> {
                        songRepository
                            .getLikedSongs()
                            .first()
                            .map { it.toMediaItem(parentId) }
                    }

                    DOWNLOADED -> {
                        songRepository
                            .getDownloadedSongs()
                            .first()
                            ?.map { it.toMediaItem(parentId) }
                            ?: emptyList()
                    }

                    PLAYLIST -> {
                        localPlaylistRepository
                            .getAllLocalPlaylists()
                            .first()
                            .sortedBy { it.inLibrary }
                            .map {
                                browsableMediaItem(
                                    "$PLAYLIST/${it.id}",
                                    it.title,
                                    "${it.tracks?.size ?: 0} ${context.getString(R.string.track)}",
                                    it.thumbnail?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }
                    }

                    HOME -> {
                        val temp =
                            homeRepository
                                .getHomeData(
                                    viewString = context.getString(R.string.view_count),
                                    songString = context.getString(R.string.song),
                                ).lastOrNull()
                                ?.data
                        listHomeItem.clear()
                        listHomeItem.addAll(temp?.second ?: emptyList())
                        if (!temp?.first.isNullOrEmpty()) {
                            var continueParam = temp.first
                            // Cap the continuation crawl: fetching every home shelf before
                            // replying made browsing on the head unit hang for many seconds.
                            var pages = 0
                            while (continueParam != null && pages < HOME_MAX_CONTINUATIONS) {
                                homeRepository
                                    .getHomeDataContinue(
                                        continueParam,
                                        viewString = context.getString(R.string.view_count),
                                        songString = context.getString(R.string.song),
                                    ).lastOrNull()
                                    .let {
                                        listHomeItem.addAll(it?.data?.second ?: emptyList())
                                        continueParam = it?.data?.first
                                    }
                                pages++
                            }
                        }
                        listHomeItem.map {
                            browsableMediaItem(
                                "$HOME/${it.title}",
                                it.title,
                                it.subtitle,
                                null,
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            )
                        }
                    }

                    else -> {
                        when {
                            parentId.startsWith("$HOME/") -> {
                                if (parentId.split("/").size == 2) {
                                    val homeItem =
                                        listHomeItem.find {
                                            it.title == parentId.split("/").getOrNull(1)
                                        }
                                    homeItem
                                        ?.contents
                                        ?.filter { it?.playlistId != null || it?.videoId != null }
                                        ?.mapNotNull {
                                            if (it?.playlistId != null) {
                                                browsableMediaItem(
                                                    id = "$HOME/${homeItem.title}/$PLAYLIST/${it.playlistId}",
                                                    title = it.title,
                                                    subtitle = it.description,
                                                    iconUri =
                                                        it.thumbnails
                                                            .lastOrNull()
                                                            ?.url
                                                            ?.toUri(),
                                                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                                )
                                            } else if (it?.videoId != null) {
                                                it
                                                    .toTrack()
                                                    .toSongEntity()
                                                    .toMediaItem("$HOME/${homeItem.title}/$SONG")
                                            } else {
                                                null
                                            }
                                        }
                                        ?: emptyList()
                                } else {
                                    val playlistId = parentId.split("/").getOrNull(3)
                                    if (playlistId != null) {
                                        val playlist =
                                            playlistRepository
                                                .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                                .lastOrNull()
                                        if (playlist?.data?.tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            playlist.data?.toPlaylistEntity()?.let { playlistRepository.insertAndReplacePlaylist(it) }
                                            playlist.data?.tracks?.map { track ->
                                                track
                                                    .toSongEntity()
                                                    .also {
                                                        songRepository.insertSong(it).first()
                                                    }.toMediaItem(parentId)
                                            } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

                            parentId.startsWith("$PLAYLIST/") -> {
                                val playlistId = parentId.split("/").getOrNull(1)
                                if (playlistId != null) {
                                    val playlist =
                                        localPlaylistRepository.getLocalPlaylist(playlistId.toLong()).lastOrNull()?.data
                                    if (playlist != null) {
                                        Logger.w(TAG, "onGetChildren: $playlist")
                                        val tracks = playlist.tracks
                                        if (tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            songRepository
                                                .getSongsByListVideoId(tracks)
                                                .lastOrNull()
                                                ?.map { it.toMediaItem(parentId) } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                },
                libraryParams,
            )
        }

    @UnstableApi
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            songRepository.getSongById(mediaId).first()?.let {
                LibraryResult.ofItem(it.toMediaItem(), null)
            } ?: streamRepository.getFullMetadata(mediaId).lastOrNull()?.data?.let {
                LibraryResult.ofItem(it.toMediaItemWithoutPath(), null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    @UnstableApi
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
            // Resolve car catalogue IDs. Media3 owns applying/preparing/playing this result.
            // Mutating the player here and returning an empty list cleared the queue again.
            if (mediaItems.all { it.localConfiguration != null }) {
                return@future MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
            val defaultResult =
                MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val path =
                mediaItems.firstOrNull()?.mediaId?.split("/")
                    ?: return@future defaultResult
            when (path.firstOrNull()) {
                SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val firstQueue = songRepository.getSongById(songId).first()?.toTrack() ?: return@future defaultResult
                    mediaPlayerHandler.setPlaybackResumptionQueue(
                        QueueData.Data(
                            listTracks = arrayListOf(firstQueue),
                            firstPlayedTrack = firstQueue,
                            playlistId = "RDAMVM$songId",
                            playlistName = "\"${firstQueue.title}\" Radio",
                            playlistType = PlaylistType.RADIO,
                            continuation = null,
                        ),
                    )
                    queueMediaItems(firstQueue.videoId, startPositionMs)
                }

                FAVORITE -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val likedSongs = songRepository.getLikedSongs().first()
                    if (likedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        val clickedSong =
                            likedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setPlaybackResumptionQueue(
                            QueueData.Data(
                                listTracks = likedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.favorites),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        queueMediaItems(clickedSong.videoId, startPositionMs)
                    }
                }

                DOWNLOADED -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val downloadedSongs = songRepository.getDownloadedSongs().first().orEmpty()
                    if (downloadedSongs.isEmpty()) {
                        defaultResult
                    } else {
                        val clickedSong =
                            downloadedSongs
                                .firstOrNull { it.videoId == songId }
                                ?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setPlaybackResumptionQueue(
                            QueueData.Data(
                                listTracks = downloadedSongs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = null,
                                playlistName = context.getString(R.string.downloaded),
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        queueMediaItems(clickedSong.videoId, startPositionMs)
                    }
                }

                PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                    var title = ""
                    val songs =
                        localPlaylistRepository
                            .getLocalPlaylist(playlistId.toLong())
                            .lastOrNull()
                            ?.data
                            ?.also {
                                title = it.title
                            }?.tracks
                            ?.let {
                                songRepository.getSongsByListVideoId(it)
                            }?.lastOrNull()
                    Logger.w(TAG, "onSetMediaItems songs: $songs")
                    if (songs.isNullOrEmpty()) {
                        defaultResult
                    } else {
                        val clickedSong =
                            songs
                                .firstOrNull { it.videoId == songId }
                                ?.toTrack() ?: return@future defaultResult
                        mediaPlayerHandler.setPlaybackResumptionQueue(
                            QueueData.Data(
                                listTracks = songs.toArrayListTrack(),
                                firstPlayedTrack = clickedSong,
                                playlistId = playlistId,
                                playlistName = "${
                                    context.getString(
                                        R.string.playlists,
                                    )
                                } \"${title}\"",
                                playlistType = PlaylistType.LOCAL_PLAYLIST,
                                continuation = null,
                            ),
                        )
                        queueMediaItems(clickedSong.videoId, startPositionMs)
                    }
                }

                HOME -> {
                    val type = path.getOrNull(2) ?: return@future defaultResult
                    val content = listHomeItem.find { it.title == path.getOrNull(1) }?.contents
                    if (type == SONG) {
                        val songId = path.getOrNull(3) ?: return@future defaultResult
                        val songs =
                            content?.filter { it?.videoId != null }?.mapNotNull {
                                it?.toTrack()
                            }
                        if (songs.isNullOrEmpty()) {
                            defaultResult
                        } else {
                            songs.forEach {
                                songRepository.insertSong(it.toSongEntity()).first()
                            }
                            val firstQueue = songs.firstOrNull { it.videoId == songId } ?: return@future defaultResult
                            mediaPlayerHandler.setPlaybackResumptionQueue(
                                QueueData.Data(
                                    listTracks = songs,
                                    firstPlayedTrack = firstQueue,
                                    playlistId = "RDAMVM$songId",
                                    playlistName = "\"${firstQueue.title}\" Radio",
                                    playlistType = PlaylistType.RADIO,
                                    continuation = null,
                                ),
                            )
                            queueMediaItems(firstQueue.videoId, startPositionMs)
                        }
                    } else if (type == PLAYLIST) {
                        val songId = path.getOrNull(4) ?: return@future defaultResult
                        val playlistId = path.getOrNull(3) ?: return@future defaultResult
                        Logger.d(TAG, "onSetMediaItems playlistId: $playlistId")
                        val playlistEntity = playlistRepository.getPlaylist(playlistId).first()
                        Logger.w(TAG, "onSetMediaItems playlistEntity: $playlistEntity")
                        val tracks = playlistEntity?.tracks
                        if (tracks.isNullOrEmpty()) {
                            defaultResult
                        } else if (tracks.isNotEmpty()) {
                            val songs =
                                tracks
                                    .let {
                                        songRepository
                                            .getSongsByListVideoId(tracks)
                                            .first()
                                            .sortedBy {
                                                tracks.indexOf(it.videoId)
                                            }.also {
                                                Logger.w(TAG, "onSetMediaItems list songs: $it")
                                            }
                                    }
                            val clickedSong =
                                songs
                                    .firstOrNull { it.videoId == songId }
                                    ?.toTrack() ?: return@future defaultResult
                            mediaPlayerHandler.setPlaybackResumptionQueue(
                                QueueData.Data(
                                    listTracks = songs.toArrayListTrack(),
                                    firstPlayedTrack = clickedSong,
                                    playlistId = playlistId,
                                    playlistName = "${
                                        context.getString(
                                            R.string.playlists,
                                        )
                                    } \"${playlistEntity.title}\"",
                                    playlistType = PlaylistType.LOCAL_PLAYLIST,
                                    continuation = null,
                                ),
                            )
                            queueMediaItems(clickedSong.videoId, startPositionMs)
                        } else {
                            defaultResult
                        }
                    } else {
                        return@future defaultResult
                    }
                }

                else -> {
                    defaultResult
                }
            }
        }

    private fun queueMediaItems(videoId: String, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val queue = mediaPlayerHandler.queueData.value?.data ?: throw IllegalStateException("Missing playback queue")
        val index = queue.listTracks.indexOfFirst { it.videoId == videoId }.coerceAtLeast(0)
        pendingRadioTrack = if (queue.playlistType == PlaylistType.RADIO) videoId else null
        return MediaSession.MediaItemsWithStartPosition(
            queue.listTracks.map { it.toMediaItem() },
            index,
            startPositionMs,
        )
    }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun SongEntity.toMediaItem(path: String) =
        MediaItem
            .Builder()
            .setMediaId("$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artistName?.joinToString(", "))
                    .setArtist(this.artistName?.joinToString(" "))
                    .setArtworkUri(this.thumbnails?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    private fun Track.toMediaItemWithoutPath(path: String? = SONG) =
        MediaItem
            .Builder()
            .setMediaId(if (path == null) this.videoId else "$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artists?.toListName()?.connectArtists())
                    .setArtist(this.artists?.toListName()?.connectArtists())
                    .setArtworkUri(
                        this.thumbnails
                            ?.lastOrNull()
                            ?.url
                            ?.toUri(),
                    ).setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val HOME = "home"
        const val ONLINE_PLAYLIST = "online_playlist"
        const val PLAYLIST = "playlist"
        const val FAVORITE = "favorite"
        const val DOWNLOADED = "downloaded"
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
