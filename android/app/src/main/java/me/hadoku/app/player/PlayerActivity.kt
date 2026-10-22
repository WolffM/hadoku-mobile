package me.hadoku.app.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Plays one downloaded episode from the app's private external files dir.
 * Media3's own PlayerView supplies the audio-language and subtitle menus.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    lateinit var player: ExoPlayer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaId = requireNotNull(intent.getStringExtra(EXTRA_MEDIA_ID)) { "missing $EXTRA_MEDIA_ID" }
        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(episodeMediaSource(this@PlayerActivity, EpisodeFiles.from(episodeDir(this@PlayerActivity, mediaId))))
            prepare()
            playWhenReady = true
        }
        setContentView(PlayerView(this).also {
            it.player = player
            it.setShowSubtitleButton(true)
            it.keepScreenOn = true
            it.setBackgroundColor(Color.BLACK)
        })
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EPISODES = "episodes"

        /** Where an episode lives: app-private, so no storage permission and gone on uninstall. */
        fun episodeDir(context: Context, mediaId: String): File =
            File(context.getExternalFilesDir(EPISODES), mediaId)

        fun intent(context: Context, mediaId: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}
