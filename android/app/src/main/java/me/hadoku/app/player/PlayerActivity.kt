package me.hadoku.app.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import me.hadoku.app.library.Downloads

/**
 * Plays one downloaded episode from the app's private external files dir.
 * Media3's own PlayerView supplies the audio-language and subtitle menus.
 *
 * For an episode in the Library it starts where the viewer stopped and saves
 * the position whenever it leaves the screen. Near the end counts as
 * finished, so the next play starts over rather than on the credits.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    lateinit var player: ExoPlayer
        private set
    private lateinit var mediaId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = requireNotNull(intent.getStringExtra(EXTRA_MEDIA_ID)) { "missing $EXTRA_MEDIA_ID" }
        val resumeAt = Downloads.store(this).get(mediaId)?.positionMs ?: 0
        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(episodeMediaSource(this@PlayerActivity, EpisodeFiles.from(episodeDir(this@PlayerActivity, mediaId))), resumeAt)
            prepare()
            playWhenReady = true
        }
        setContentView(PlayerView(this).also {
            it.player = player
            it.setShowSubtitleButton(true)
            it.keepScreenOn = true
            it.setBackgroundColor(Color.BLACK)
        })
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        val duration = player.duration
        val at = player.currentPosition
        // The last 30 s (a tenth, for a short clip) is the credits: done, start over next time.
        val margin = minOf(FINISHED_MARGIN_MS, duration / 10)
        val finished = player.playbackState == Player.STATE_ENDED || (duration > 0 && at > duration - margin)
        Downloads.store(this).update(mediaId) { it.copy(positionMs = if (finished) 0 else at) }
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EPISODES = "episodes"
        private const val FINISHED_MARGIN_MS = 30_000L

        /** Where an episode lives: app-private, so no storage permission and gone on uninstall. */
        fun episodeDir(context: Context, mediaId: String): File =
            File(context.getExternalFilesDir(EPISODES), mediaId)

        fun intent(context: Context, mediaId: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}
