package me.hadoku.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * The screen for one downloaded episode, from the app's private external
 * files dir. The player itself lives in PlaybackService; this connects to it
 * while visible. Media3's own PlayerView supplies the audio-language and
 * subtitle menus.
 *
 * Leaving with Back stops the episode. Leaving any other way (Home, the
 * screen going off) keeps it playing as audio: the video track is switched
 * off until the screen comes back, so nothing decodes frames nobody sees.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    /** The service's player, as this screen sees it; null until connected. */
    var player: MediaController? = null
        private set
    private var connecting: ListenableFuture<MediaController>? = null
    private lateinit var mediaId: String
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = requireNotNull(intent.getStringExtra(EXTRA_MEDIA_ID)) { "missing $EXTRA_MEDIA_ID" }
        view = PlayerView(this).also {
            it.setShowSubtitleButton(true)
            it.keepScreenOn = true
            it.setBackgroundColor(Color.BLACK)
        }
        setContentView(view)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connecting = future
        future.addListener({ if (connecting === future) attach(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun attach(controller: MediaController) {
        player = controller
        // Back from the notification to the episode already playing: carry on.
        // Anything else (another episode, or this one stopped or finished)
        // starts it from its saved position, which the service looks up.
        val state = controller.playbackState
        if (controller.currentMediaItem?.mediaId != mediaId || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            controller.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
            controller.prepare()
            controller.play()
        }
        controller.showVideo(true)
        view.player = controller
    }

    override fun onStop() {
        player?.let { if (isFinishing) it.stop() else it.showVideo(false) }
        view.player = null
        player = null
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
        super.onStop()
    }

    private fun Player.showVideo(show: Boolean) {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !show)
            .build()
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
