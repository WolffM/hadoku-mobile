package me.hadoku.app.player

import androidx.media3.common.Player
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An episode longer than the player buffers ahead, which every real one is.
 * Watchparty's video file states no duration, so the time bar's total has to
 * come from the Library's record: it must be there from the first seconds,
 * not only once the file has been read to its end.
 */
@RunWith(AndroidJUnit4::class)
class FullLengthEpisodeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = Downloads.store(context)
    private val id = "full-length/${Fixture.LONG}"

    @Before
    fun save() {
        Fixture.copyTo(store.dir(id), Fixture.LONG)
        val video = File(store.dir(id), EpisodeFiles.VIDEO)
        store.put(
            Episode(
                mediaId = id,
                title = "Full length",
                series = "player",
                token = "fixture",
                durationMs = Fixture.LONG_DURATION_MS,
                addedAt = System.currentTimeMillis(),
                files = listOf(Episode.FileState(EpisodeFiles.VIDEO, video.length(), downloadId = -1, done = true)),
            )
        )
    }

    @After
    fun clean() = store.delete(id)

    @Test
    fun theTimeBarKnowsTheLengthFromTheStart() {
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context, id)).use { scenario ->
            waitFor("playing") { it.isPlaying && it.currentPosition >= 2_000 }
            // The premise: the player has NOT read to the end, so it cannot have measured the length.
            val buffered = onPlayer { it.bufferedPosition }
            assertTrue("buffered to $buffered ms: the fixture is too short to test this", buffered < Fixture.LONG_DURATION_MS - 10_000)
            assertEquals("the player's duration", Fixture.LONG_DURATION_MS, onPlayer { it.duration })
            // And the screen's own controller, which is what the time bar draws from.
            var onScreen = 0L
            scenario.onActivity { onScreen = it.player!!.duration }
            assertEquals("the screen's duration", Fixture.LONG_DURATION_MS, onScreen)

            onPlayer { it.seekTo(125_000) }
            waitFor("playing after a seek past what was buffered") { it.isPlaying && it.currentPosition in 125_500..130_000 }

            onPlayer { it.seekTo(Fixture.LONG_DURATION_MS - 3_000) }
            waitFor("the episode ending") { it.playbackState == Player.STATE_ENDED }
        }
    }

    private fun <T> onPlayer(read: (Player) -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read(PlaybackService.player!!) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitFor(what: String, condition: (Player) -> Boolean) =
        Device.waitUntil(what, 15_000) {
            var ok = false
            instrumentation.runOnMainSync { ok = PlaybackService.player?.let(condition) ?: false }
            ok
        }
}
