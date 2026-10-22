package me.hadoku.app.player

import android.app.Notification
import android.app.NotificationManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.hadoku.app.Device
import me.hadoku.app.Fixture
import me.hadoku.app.library.Downloads
import me.hadoku.app.library.Episode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An episode keeps playing as audio once the player screen is gone: the media
 * notification is up, the system's media keys (what the lock screen and a
 * headset send) reach it, the notification leads back to the same episode
 * still running, and Back is what stops it.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = Downloads.store(context)
    private val id = "background/${Fixture.ID}"

    @Before
    fun save() {
        Fixture.copyTo(store.dir(id))
        val video = File(store.dir(id), EpisodeFiles.VIDEO)
        store.put(
            Episode(
                mediaId = id,
                title = "Background",
                series = "player",
                token = "fixture",
                durationMs = 20_020,
                addedAt = System.currentTimeMillis(),
                files = listOf(Episode.FileState(EpisodeFiles.VIDEO, video.length(), downloadId = -1, done = true)),
            )
        )
    }

    @After
    fun clean() = store.delete(id)

    @Test
    fun keepsPlayingInTheBackgroundUntilBack() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, id)).use {
            waitFor("playing $id") { it.currentMediaItem?.mediaId == id && it.isPlaying }
            assertTrue("video on screen", onPlayer { videoSelected(it) })

            Device.shell("input keyevent KEYCODE_HOME")
            Device.waitUntil("player screen gone", 5_000) { Device.resumed() !is PlayerActivity }
            val left = onPlayer { it.currentPosition }
            Thread.sleep(3_000)
            assertTrue("still playing", onPlayer { it.isPlaying })
            val played = onPlayer { it.currentPosition } - left
            assertTrue("played $played ms in the background", played >= 2_000)
            assertFalse("video decoding with nobody watching", onPlayer { videoSelected(it) })

            val notification = mediaNotification(5_000)
            assertEquals("Background", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())

            Device.shell("cmd media_session dispatch pause")
            waitFor("paused by the media key") { !it.isPlaying }
            val paused = onPlayer { it.currentPosition }
            Device.waitUntil("position saved on pause", 5_000) { store.get(id)!!.positionMs in (paused - 500)..(paused + 500) }
            Device.shell("cmd media_session dispatch play")
            waitFor("playing again by the media key") { it.isPlaying }

            Device.shell("input keyevent KEYCODE_SLEEP")
            try {
                val dark = onPlayer { it.currentPosition }
                Thread.sleep(3_000)
                assertTrue("playing with the screen off", onPlayer { it.isPlaying && it.currentPosition - dark >= 2_000 })
                // What the lock screen shows: the platform session, playing.
                val sessions = Device.shell("dumpsys media_session")
                assertTrue("no playing session for ${context.packageName} in\n$sessions", playingSession.containsMatchIn(sessions))
            } finally {
                Device.shell("input keyevent KEYCODE_WAKEUP")
                Device.shell("wm dismiss-keyguard")
            }

            notification.contentIntent.send()
            val screen = Device.waitForResumed(PlayerActivity::class.java)
            waitFor("video back on screen") { videoSelected(it) }
            assertTrue("carried on rather than starting over", onPlayer { it.currentPosition } >= paused)

            instrumentation.runOnMainSync { screen.finish() }
            waitFor("stopped by Back") { !it.isPlaying && it.playbackState == Player.STATE_IDLE }
            Device.waitUntil("notification gone", 10_000) { mediaNotifications().isEmpty() }
        }
    }

    private val playingSession = Regex("package=${Regex.escape(context.packageName)}(?:(?!package=)[\\s\\S])*?state=PlaybackState \\{state=PLAYING")

    private fun videoSelected(p: ExoPlayer) =
        p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }

    private fun mediaNotifications(): List<Notification> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .map { it.notification }
            .filter { it.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }

    private fun mediaNotification(timeoutMs: Long): Notification {
        Device.waitUntil("media notification", timeoutMs) { mediaNotifications().isNotEmpty() }
        return mediaNotifications().single()
    }

    private fun <T> onPlayer(block: (ExoPlayer) -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching { block(PlaybackService.player!!) } }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, condition: (ExoPlayer) -> Boolean) =
        Device.waitUntil(what, 10_000) {
            var met = false
            instrumentation.runOnMainSync { met = PlaybackService.player?.let(condition) ?: false }
            met
        }
}
