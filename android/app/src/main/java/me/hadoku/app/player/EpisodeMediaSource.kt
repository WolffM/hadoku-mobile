package me.hadoku.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * The bytes exactly as watchparty serves them to MSE, merged back into one
 * timeline: the video file carries the subtitles as side-loaded WebVTT, and
 * every audio language is its own progressive source, so DefaultTrackSelector
 * switches language without re-preparing. All files start at t=0 (ingest
 * writes them that way), so no period offset is adjusted.
 */
@OptIn(UnstableApi::class)
fun episodeMediaSource(context: Context, files: EpisodeFiles): MediaSource {
    val data = DefaultDataSource.Factory(context)
    val subtitles = files.subtitles.map {
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(it.file))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(it.language)
            .build()
    }
    val video = DefaultMediaSourceFactory(data).createMediaSource(
        MediaItem.Builder()
            .setUri(Uri.fromFile(files.video))
            .setSubtitleConfigurations(subtitles)
            .build()
    )
    val progressive = ProgressiveMediaSource.Factory(data)
    val audio = files.audio.map { progressive.createMediaSource(MediaItem.fromUri(Uri.fromFile(it.file))) }
    return MergingMediaSource(video, *audio.toTypedArray())
}
