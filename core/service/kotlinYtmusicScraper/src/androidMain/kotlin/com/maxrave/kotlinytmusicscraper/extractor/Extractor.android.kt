package com.maxrave.kotlinytmusicscraper.extractor

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress
import com.maxrave.logger.Logger
import dev.maxrave.pipepipe.extractor.NewPipe
import dev.maxrave.pipepipe.extractor.ServiceList
import dev.maxrave.pipepipe.extractor.stream.StreamInfo
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import org.schabi.newpipe.extractor.NewPipe as BraveNewPipe
import org.schabi.newpipe.extractor.ServiceList as BraveServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo as BraveStreamInfo

private const val TAG = "Extractor"

actual class Extractor {
    private var newPipeDownloader = NewPipeDownloaderImpl(proxy = null)
    private var braveNewPipeDownloader = BraveNewPipeDownloaderImpl(proxy = null)

    actual fun regularVideoPlayer(videoId: String): com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse {
        val info = BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val audio = info.audioStreams
        val streams = audio + info.videoStreams + info.videoOnlyStreams
        val formats = streams.filter { !it.content.contains(".m3u8") && !it.content.contains("/manifest/") }.mapNotNull { stream ->
            val itag = stream.itagItem ?: return@mapNotNull null
            val isAudio = stream in audio
            com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse.StreamingData.Format(
                itag = itag.id, url = stream.content, mimeType = stream.format?.mimeType ?: if (isAudio) "audio/webm" else "video/mp4",
                bitrate = itag.bitrate, width = if (isAudio) null else itag.width.coerceAtLeast(1), height = if (isAudio) null else itag.height,
                contentLength = itag.contentLength, quality = itag.quality.orEmpty(), fps = if (isAudio) null else itag.fps,
                qualityLabel = if (isAudio) null else itag.resolutionString, averageBitrate = itag.averageBitrate,
                audioQuality = if (isAudio) "AUDIO_QUALITY_MEDIUM" else null, approxDurationMs = (info.duration * 1000).toString(),
                audioSampleRate = if (isAudio) itag.sampleRate else null, audioChannels = if (isAudio) itag.audioChannels else null,
                loudnessDb = null, lastModified = itag.lastModified, signatureCipher = null,
            )
        }
        check(formats.any { it.isAudio } && formats.any { !it.isAudio }) { "No playable YouTube audio/video streams" }
        return com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse(
            responseContext = com.maxrave.kotlinytmusicscraper.models.ResponseContext(serviceTrackingParams = null),
            playabilityStatus = com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse.PlayabilityStatus("OK", null),
            playerConfig = null,
            streamingData = com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse.StreamingData(null, emptyList(), formats, 3600),
            videoDetails = com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse.VideoDetails(
                videoId, info.name, info.uploaderName, info.uploaderUrl.orEmpty(), null, null, info.duration.toString(), "VIDEO", null,
                com.maxrave.kotlinytmusicscraper.models.Thumbnails(info.thumbnails.map { com.maxrave.kotlinytmusicscraper.models.Thumbnail(it.url, null, null) }), null,
            ), playbackTracking = null, captions = com.maxrave.kotlinytmusicscraper.models.youtube.YouTubeInitialPage.Captions(
                com.maxrave.kotlinytmusicscraper.models.youtube.YouTubeInitialPage.Captions.PlayerCaptionsTracklistRenderer(
                    info.subtitles.map { subtitle ->
                        com.maxrave.kotlinytmusicscraper.models.youtube.YouTubeInitialPage.Captions.PlayerCaptionsTracklistRenderer.CaptionTrack(
                            subtitle.content, com.maxrave.kotlinytmusicscraper.models.youtube.YouTubeInitialPage.Captions.PlayerCaptionsTracklistRenderer.CaptionTrack.Name(subtitle.displayLanguageName), subtitle.languageTag,
                        )
                    },
                ),
            ),
        )
    }

    actual fun searchVideos(query: String): List<RegularVideo> {
        val extractor = BraveServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(extractor)
        return info.relatedItems.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().mapNotNull { item ->
            val id = item.url.substringAfter("v=", "").substringBefore("&").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RegularVideo(id, item.name, item.uploaderName.orEmpty(), item.duration.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), item.thumbnails.firstOrNull()?.url.orEmpty())
        }
    }

    actual fun init() {
        NewPipe.init(newPipeDownloader)
        BraveNewPipe.init(braveNewPipeDownloader)
    }

    actual fun logIn(cookie: String?) {
        ServiceList.YouTube.tokens = cookie ?: ""
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        try {
            val streamInfo =
                StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            val pipeResult =
                streamsList.mapNotNull {
                    (it.itagItem?.id ?: return@mapNotNull null) to it.content
                }
            if (!pipeResult.hasRequiredItags()) {
                Logger.d(
                    TAG,
                    "PipePipe missing required itags for $videoId (got=${pipeResult.map { it.first }}), falling back to BravePipe",
                )
            } else if (!pipeResult.headCheckRandomStream()) {
                Logger.d(
                    TAG,
                    "PipePipe stream URL HEAD check failed (non 2xx) for $videoId, falling back to BravePipe",
                )
            } else {
                return pipeResult
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "PipePipe extractor failed for $videoId: ${e.message}, falling back to BravePipe")
        }

        return runCatching {
            val streamInfo =
                BraveStreamInfo.getInfo(BraveServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        }.onFailure {
            Logger.w(TAG, "BravePipe extractor failed for $videoId: ${it.message}")
        }.getOrElse { emptyList() }
    }

    actual fun mergeAudioVideoDownload(filePath: String): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.mp4"),
                "-i",
                ("$filePath.webm"),
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-shortest",
                "$filePath-Nothing Player.mp4",
            ).joinToString(" ")

        if (FileSystem.SYSTEM.exists("$filePath-Nothing Player.mp4".toPath())) {
            FileSystem.SYSTEM.delete("$filePath-Nothing Player.mp4".toPath())
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.VIDEO_DONE)
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "Command cancelled"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath.mp4".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed(session.failStackTrace ?: "FFmpeg command failed"))
        }
    }

    actual fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress {
        val command =
            listOf(
                "-i",
                ("$filePath.webm"),
                "-q:a 0",
                "$filePath.mp3",
            ).joinToString(" ")

        try {
            if (FileSystem.SYSTEM.exists("$filePath.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
            }
            if (FileSystem.SYSTEM.exists("$filePath-nothingplayer.mp3".toPath())) {
                FileSystem.SYSTEM.delete("$filePath-nothingplayer.mp3".toPath())
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val session =
            FFmpegKit.execute(
                command,
            )
        if (ReturnCode.isSuccess(session.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else if (ReturnCode.isCancel(session.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${session.state}, ${session.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${session.state}, ${session.returnCode}, ${session.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }

        val commandInject =
            listOf(
                "-i",
                "$filePath.mp3",
                "-i $filePath.jpg",
                "-map 0:a",
                "-map 1:v",
                "-c copy",
                "-id3v2_version 3",
                "-metadata",
                "title=\"${track.title}\"",
                "-metadata",
                "artist=\"${track.artists.joinToString(", ") { it.name }}\"",
                "-metadata",
                "album=\"${track.album?.name ?: track.title}\"",
                "-disposition:v:0 attached_pic",
                "$filePath-nothingplayer.mp3",
            ).joinToString(" ")
        val sessionInject =
            FFmpegKit.execute(
                commandInject,
            )
        if (ReturnCode.isSuccess(sessionInject.returnCode)) {
            // SUCCESS
            Logger.d(TAG, "Command succeeded ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.mp3".toPath())
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.AUDIO_DONE)
        } else if (ReturnCode.isCancel(sessionInject.returnCode)) {
            // CANCEL
            Logger.d(TAG, "Command cancelled ${sessionInject.state}, ${sessionInject.returnCode}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-nothingplayer.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        } else {
            // FAILURE
            Logger.d(TAG, "Command failed ${sessionInject.state}, ${sessionInject.returnCode}, ${sessionInject.failStackTrace}")
            try {
                FileSystem.SYSTEM.delete("$filePath.jpg".toPath())
                FileSystem.SYSTEM.delete("$filePath.webm".toPath())
                FileSystem.SYSTEM.delete("$filePath-nothingplayer.mp3".toPath())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return (DownloadProgress.failed("Error"))
        }
    }
}