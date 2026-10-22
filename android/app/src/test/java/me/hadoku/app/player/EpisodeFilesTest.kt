package me.hadoku.app.player

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpisodeFilesTest {
    private fun episode(vararg paths: String): File {
        val dir = Files.createTempDirectory("episode").toFile()
        for (p in paths) File(dir, p).apply { parentFile!!.mkdirs() }.writeText("")
        return dir
    }

    @Test
    fun readsWatchpartyLayout() {
        val dir = episode(
            "output_frag.mp4",
            "audio/audio_jpn_2.m4a",
            "audio/audio_eng_1.m4a",
            "subs/sub_ita.vtt",
            "subs/sub_eng.vtt",
            "index.json",
        )
        val files = EpisodeFiles.from(dir)
        assertEquals(File(dir, "output_frag.mp4"), files.video)
        assertEquals(listOf("eng", "jpn"), files.audio.map { it.language })
        assertEquals(listOf("eng", "ita"), files.subtitles.map { it.language })
        assertEquals(File(dir, "audio/audio_eng_1.m4a"), files.audio[0].file)
    }

    @Test
    fun ignoresFilesThatAreNotTracks() {
        val dir = episode("output_frag.mp4", "audio/audio_report.json", "subs/sub_eng.vtt.part", "subs/notes.txt")
        val files = EpisodeFiles.from(dir)
        assertEquals(emptyList<EpisodeFiles.Track>(), files.audio)
        assertEquals(emptyList<EpisodeFiles.Track>(), files.subtitles)
    }

    @Test
    fun anEpisodeWithoutAudioOrSubsDirsStillReads() {
        val files = EpisodeFiles.from(episode("output_frag.mp4"))
        assertEquals(0, files.audio.size + files.subtitles.size)
    }

    @Test
    fun missingVideoIsAnError() {
        val dir = episode("audio/audio_eng_1.m4a")
        assertThrows(IllegalArgumentException::class.java) { EpisodeFiles.from(dir) }
    }
}
