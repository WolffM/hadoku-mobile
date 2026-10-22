package me.hadoku.app.player

import java.io.File

/**
 * One episode as watchparty lays it out on disk, and as the Library stores it:
 *
 *   <dir>/output_frag.mp4          video only, H.264 fMP4 muxed for MSE
 *   <dir>/audio/audio_<lang>_<n>.m4a  one AAC fMP4 per language
 *   <dir>/subs/sub_<lang>.vtt      one WebVTT per language
 *
 * Languages are the ISO 639-2 codes in the file names. The audio files carry
 * the same code in their own `mdhd`, but a VTT has none, so the file name is
 * the only source for subtitles and is used for both, to keep them consistent.
 */
data class EpisodeFiles(
    val video: File,
    val audio: List<Track>,
    val subtitles: List<Track>,
) {
    data class Track(val language: String, val file: File)

    companion object {
        const val VIDEO = "output_frag.mp4"
        private val AUDIO = Regex("""audio_([a-z]{3})_\d+\.m4a""")
        private val SUBTITLE = Regex("""sub_([a-z]{3})\.vtt""")

        /** Reads [dir]; throws if the video is missing, since nothing plays without it. */
        fun from(dir: File): EpisodeFiles {
            val video = File(dir, VIDEO)
            require(video.isFile) { "no $VIDEO in $dir" }
            return EpisodeFiles(
                video = video,
                audio = tracks(File(dir, "audio"), AUDIO),
                subtitles = tracks(File(dir, "subs"), SUBTITLE),
            )
        }

        private fun tracks(dir: File, pattern: Regex): List<Track> =
            dir.listFiles().orEmpty()
                .mapNotNull { f -> pattern.matchEntire(f.name)?.let { Track(it.groupValues[1], f) } }
                .sortedBy { it.file.name }
    }
}
