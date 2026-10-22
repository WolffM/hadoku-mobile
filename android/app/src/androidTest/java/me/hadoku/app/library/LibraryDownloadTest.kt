package me.hadoku.app.library

import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import me.hadoku.app.Device
import me.hadoku.app.Hadoku
import me.hadoku.app.LiveProduction
import me.hadoku.app.MainActivity
import me.hadoku.app.player.EpisodeFiles
import me.hadoku.app.player.PlaybackService
import me.hadoku.app.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library v1's acceptance test, "usable on a flight", against production:
 * browse the catalog to an episode, download it with the default choices,
 * check the video is the encode the server names (content_token is its
 * sha256), then cut the network, cold-launch the app, and watch it from the
 * Library. Needs a signed-in emulator; moves ~560 MB. `pnpm test:library-live`.
 */
@LiveProduction
@RunWith(AndroidJUnit4::class)
class LibraryDownloadTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val path = (args.getString("path") ?: "anime/Frieren/frieren-s01e01").split('/')
    private val mediaId = "${Watchparty.ROOT}/${path.joinToString("/")}"

    @Test
    fun downloadThenWatchOffline() {
        assertTrue("no hadoku.me session on this device; run pnpm emulator:sign-in", Hadoku.session() != null)
        Downloads.delete(context, mediaId)
        val started = System.currentTimeMillis()

        ActivityScenario.launch(LibraryActivity::class.java).use {
            compose.onNodeWithText("Browse watchparty").performClick()
            for (step in path) {
                compose.waitUntil(30_000) { compose.onAllNodes(hasText(step)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(step).performClick()
            }
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Download ·", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Download ·", substring = true)).performClick()
            Device.waitUntil("$mediaId downloaded", 30 * 60_000L) {
                val e = Downloads.store(context).get(mediaId)
                check(e?.failed != true) { "download failed: $e" }
                e?.ready == true
            }
        }
        val episode = Downloads.store(context).get(mediaId)!!
        Log.i(TAG, "downloaded ${episode.files.size} files, ${episode.files.sumOf { it.bytes }} bytes in ${(System.currentTimeMillis() - started) / 1000} s")
        val dir = Downloads.store(context).dir(mediaId)
        assertEquals("video sha256 vs content_token", episode.token, sha256(File(dir, EpisodeFiles.VIDEO)))
        val files = EpisodeFiles.from(dir)
        assertTrue("audio ${files.audio}", files.audio.isNotEmpty())

        Device.airplane(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Device.waitForResumed(LibraryActivity::class.java)
                compose.onNodeWithContentDescription("Play").performClick()
                val player = Device.waitForResumed(PlayerActivity::class.java)
                Device.waitUntil("5 s played offline", 30_000) {
                    var at = 0L
                    instrumentation.runOnMainSync { at = PlaybackService.player?.currentPosition ?: 0 }
                    at >= 5_000
                }
                instrumentation.runOnMainSync { player.finish() }
            }
        } finally {
            Device.airplane(false)
        }
        Downloads.delete(context, mediaId)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "HadokuLibraryTest"
    }
}
