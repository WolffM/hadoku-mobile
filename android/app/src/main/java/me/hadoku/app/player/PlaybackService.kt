package me.hadoku.app.player

import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import me.hadoku.app.library.Downloads

/**
 * Owns the one player, so an episode keeps playing (as audio) after the
 * screen goes off or the viewer switches apps. The media session gives it a
 * notification, lock-screen and headset controls; PlayerActivity is just a
 * window onto it, connected through a MediaController.
 *
 * Not exported: only this app binds it. The lock screen, Bluetooth and the
 * system media controls reach the session through its platform token.
 *
 * A controller asks for an episode by mediaId alone. The service fills in the
 * title and the saved position from the Library's record, and saves the
 * position back whenever playback stops, every few seconds while it runs
 * (a backgrounded app can be killed without warning), and before another
 * episode replaces it. Near the end counts as finished, so the next play
 * starts over rather than on the credits.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private val saveWhilePlaying = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(EpisodeSources(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saveWhilePlaying)
                if (isPlaying) handler.postDelayed(saveWhilePlaying, SAVE_EVERY_MS) else save()
            }
        })
        session = MediaSession.Builder(this, exo).setCallback(Callback()).build()
        // Stopped means the viewer pressed Back: done, so no notification to resume from.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        player = exo
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        handler.removeCallbacks(saveWhilePlaying)
        save()
        player = null
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun save() {
        val p = session.player
        val mediaId = p.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val duration = p.duration
        val at = p.currentPosition
        // The last 30 s (a tenth, for a short clip) is the credits: done, start over next time.
        val margin = minOf(FINISHED_MARGIN_MS, duration / 10)
        val finished = p.playbackState == Player.STATE_ENDED || (duration > 0 && at > duration - margin)
        Downloads.store(this).update(mediaId) { it.copy(positionMs = if (finished) 0 else at) }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            save()
            val store = Downloads.store(this@PlaybackService)
            val items = mediaItems.map { item ->
                val episode = store.get(item.mediaId)
                item.buildUpon().setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode?.title ?: item.mediaId.substringAfterLast('/'))
                        .setArtist(episode?.series)
                        .build()
                ).build()
            }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            val mediaId = items[index].mediaId
            val position = if (startPositionMs == C.TIME_UNSET) store.get(mediaId)?.positionMs ?: 0 else startPositionMs
            // Tapping the notification returns to this episode's screen.
            mediaSession.setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService, 0, PlayerActivity.intent(this@PlaybackService, mediaId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            return Futures.immediateFuture(MediaItemsWithStartPosition(items, index, position))
        }
    }

    /** A MediaItem's mediaId names an episode dir; this turns it into the merged episode source. */
    private class EpisodeSources(private val context: Context) : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            episodeMediaSource(context, mediaItem, EpisodeFiles.from(PlayerActivity.episodeDir(context, mediaItem.mediaId)))

        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
    }

    companion object {
        private const val SAVE_EVERY_MS = 5_000L
        private const val FINISHED_MARGIN_MS = 30_000L

        /** The live player, for instrumented tests that read ExoPlayer-only state (decoder counters). */
        @VisibleForTesting
        var player: ExoPlayer? = null
            private set
    }
}
