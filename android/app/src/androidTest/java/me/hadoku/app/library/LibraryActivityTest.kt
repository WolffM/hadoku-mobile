package me.hadoku.app.library

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.hadoku.app.Device
import me.hadoku.app.Fixture
import me.hadoku.app.player.EpisodeFiles
import me.hadoku.app.player.PlaybackService
import me.hadoku.app.player.PlayerActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Library with no network: a saved episode is listed as ready, plays,
 * remembers where the viewer stopped, and deletes, files and all. The
 * episode is the synthetic fixture, recorded the way a finished download is.
 */
@RunWith(AndroidJUnit4::class)
class LibraryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = Downloads.store(context)
    private val id = "test/library/${Fixture.ID}"
    // A ready row is clickable, which merges its texts into the row's node;
    // matching inside one row needs the unmerged tree.
    private val row = hasAnyAncestor(hasTestTag("episode:$id"))

    @Before
    fun save() {
        Fixture.copyTo(store.dir(id))
        val video = File(store.dir(id), EpisodeFiles.VIDEO)
        store.put(
            Episode(
                mediaId = id,
                title = Fixture.ID,
                series = "library",
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
    fun aSavedEpisodePlaysResumesAndDeletes() {
        ActivityScenario.launch(LibraryActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Ready", substring = true) and row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("Play") and row, useUnmergedTree = true).performClick()

            val player = Device.waitForResumed(PlayerActivity::class.java)
            Device.waitUntil("3 s played", 15_000) {
                var at = 0L
                instrumentation.runOnMainSync { at = PlaybackService.player?.currentPosition ?: 0 }
                at >= 3_000
            }
            instrumentation.runOnMainSync { player.finish() }
            Device.waitForResumed(LibraryActivity::class.java)
            Device.waitUntil("position saved", 5_000) { store.get(id)!!.positionMs >= 3_000 }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("resume at 00:0", substring = true) and row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

            compose.onNode(hasContentDescription("Delete") and row, useUnmergedTree = true).performClick()
            compose.onNodeWithText("Delete").performClick()
            Device.waitUntil("episode deleted", 5_000) { store.get(id) == null }
            assertFalse("files left at ${store.dir(id)}", store.dir(id).exists())
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("episode:$id")).fetchSemanticsNodes().isEmpty() }
        }
    }
}
