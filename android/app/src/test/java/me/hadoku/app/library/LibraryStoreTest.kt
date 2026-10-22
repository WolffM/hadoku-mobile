package me.hadoku.app.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun episode(id: String, addedAt: Long = 1) = Episode(
        mediaId = id,
        title = id.substringAfterLast('/'),
        series = "Frieren",
        token = "t",
        durationMs = 1_560_892,
        addedAt = addedAt,
        files = listOf(
            Episode.FileState("output_frag.mp4", 485_369_033, downloadId = 7),
            Episode.FileState("subs/sub_eng.vtt", 0, downloadId = 8, done = true),
        ),
    )

    @Test
    fun anEpisodeRoundTripsThroughItsManifest() {
        val e = episode("output/fmp4/anime/Frieren/frieren-s01e01").copy(
            positionMs = 61_000,
            problem = "changed",
            files = listOf(Episode.FileState("output_frag.mp4", 1, 2, done = false, failure = "HTTP 500")),
        )
        assertEquals(e, Episode.fromJson(e.toJson()))
        val clean = episode("a")
        assertEquals(clean, Episode.fromJson(clean.toJson()))
    }

    @Test
    fun readyOnlyWhenEveryFileIsDoneAndNothingIsWrong() {
        val e = episode("a")
        assertFalse(e.ready)
        val done = e.copy(files = e.files.map { it.copy(done = true) })
        assertTrue(done.ready)
        assertFalse(done.copy(problem = "changed").ready)
        assertTrue(e.copy(files = e.files.map { it.copy(failure = "x") }).failed)
    }

    @Test
    fun storesListsUpdatesAndDeletesNestedEpisodes() {
        val root = tmp.newFolder("episodes")
        val store = LibraryStore(root)
        store.put(episode("output/fmp4/anime/Frieren/frieren-s01e01", addedAt = 1))
        store.put(episode("output/fmp4/anime/Frieren/frieren-s01e02", addedAt = 2))
        // A pushed test episode with no manifest is not in the Library.
        File(root, "fixture-s01e01").mkdirs()

        assertEquals(listOf("frieren-s01e02", "frieren-s01e01"), store.all().map { it.title })
        store.update("output/fmp4/anime/Frieren/frieren-s01e01") { it.copy(positionMs = 5) }
        assertEquals(5L, store.get("output/fmp4/anime/Frieren/frieren-s01e01")!!.positionMs)
        assertNull(store.update("nope") { it })

        File(store.dir("output/fmp4/anime/Frieren/frieren-s01e01"), "output_frag.mp4").writeBytes(ByteArray(1000))
        assertTrue(store.bytesUsed() >= 1000)

        store.delete("output/fmp4/anime/Frieren/frieren-s01e01")
        assertNull(store.get("output/fmp4/anime/Frieren/frieren-s01e01"))
        assertEquals(listOf("frieren-s01e02"), store.all().map { it.title })
        store.delete("output/fmp4/anime/Frieren/frieren-s01e02")
        // Nothing left under output/: the empty parents go with the last episode.
        assertFalse(File(root, "output").exists())
        assertTrue(File(root, "fixture-s01e01").exists())
    }
}
