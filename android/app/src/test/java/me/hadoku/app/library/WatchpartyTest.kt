package me.hadoku.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchpartyTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name).readText()

    /**
     * Frieren s01e01's real index.json (fragments trimmed to the first two
     * and the last) and tracks.json, as the origin serves them.
     */
    private val frieren = EpisodeInfo.parse(
        "output/fmp4/anime/Frieren/frieren-s01e01",
        index = resource("frieren-s01e01/index.json"),
        tracks = resource("frieren-s01e01/tracks.json"),
    )

    @Test
    fun episodeSizesComeFromTheLastFragment() {
        // On disk: output_frag.mp4 485,383,901, audio_eng_1 38,368,448, audio_jpn_2 38,420,897.
        // The fragment map ends a few KB short of each file (trailing boxes), never past it.
        assertEquals(485_369_033L, frieren.video.bytes)
        assertEquals("output_frag.mp4", frieren.video.path)
        val audio = frieren.audio.associateBy { it.lang }
        assertEquals(setOf("eng", "jpn"), audio.keys)
        assertEquals("audio/audio_jpn_2.m4a", audio.getValue("jpn").file.path)
        assertEquals("Japanese", audio.getValue("jpn").label)
        for ((lang, onDisk) in mapOf("eng" to 38_368_448L, "jpn" to 38_420_897L)) {
            val estimate = audio.getValue(lang).file.bytes
            assertTrue("$lang $estimate vs $onDisk", estimate in (onDisk - 64 * 1024)..onDisk)
        }
    }

    @Test
    fun theTokenAndDurationAreIndexJsons() {
        // content_token is the video file's sha256.
        assertEquals("c08b034a39b756a70fb6f3361452b216ad674619738c09f3fbf34d91b1636247", frieren.token)
        assertEquals(1_560_892L, frieren.durationMs)
    }

    @Test
    fun everySubtitleIsOffered() {
        // The tracks API keeps only English; tracks.json has all eight.
        assertEquals(listOf("ara", "eng", "fre", "ger", "ita", "por", "rus", "spa"), frieren.subtitles.map { it.lang })
        assertEquals("subs/sub_eng.vtt", frieren.subtitles[1].file.path)
    }

    @Test
    fun ingestDebrisIsNotATrack() {
        val info = EpisodeInfo.parse(
            "x",
            index = """{"content_token":"t","duration_ms":1,"fragments":[{"end_byte":9}],
                "audio_tracks":[{"path":"audio/tmp1234.m4a","label":"?","fragments":[{"end_byte":1}]},
                                {"path":"audio/audio_eng_1.m4a","label":"English","fragments":[{"end_byte":4}]}]}""",
            tracks = """{"subtitles":["sub_eng.vtt","notes.txt","sub_zh-hans.vtt"]}""",
        )
        assertEquals(listOf("eng"), info.audio.map { it.lang })
        assertEquals(5L, info.audio.single().file.bytes)
        assertEquals(listOf("eng", "zh-hans"), info.subtitles.map { it.lang })
    }

    @Test
    fun catalogListsFoldersAndEpisodesButNotLooseFiles() {
        // The shape apps/server/src/catalog/index.ts returns.
        val page = CatalogPage.parse(
            """{"dirs":[{"name":"Frieren","path":"output/fmp4/anime/Frieren"}],
                "files":[{"id":"output/fmp4/anime/x/x-s01e01","title":"x-s01e01","masterUrl":"output/fmp4/anime/x/x-s01e01","poster":null,"durationMs":null},
                         {"id":"output/fmp4/anime/trailer.mp4","title":"trailer","masterUrl":"output/fmp4/anime/trailer.mp4","poster":null,"durationMs":null}]}""",
        )
        assertEquals(listOf(CatalogPage.Dir("Frieren", "output/fmp4/anime/Frieren")), page.dirs)
        assertEquals(listOf(CatalogPage.Entry("output/fmp4/anime/x/x-s01e01", "x-s01e01")), page.episodes)
    }
}
