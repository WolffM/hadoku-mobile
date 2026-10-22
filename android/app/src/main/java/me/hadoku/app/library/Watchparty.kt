package me.hadoku.app.library

import android.content.Context
import me.hadoku.app.Hadoku
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Library reads from watchparty, all friend tier with the session
 * cookie as auth:
 *
 *   /watchparty/api/catalog?prefix=<dir>         folders and episodes under a dir
 *   /watchparty/media/<id>/index.json            sizes (fragment byte ranges), content_token
 *   /watchparty/media/<id>/tracks.json           every subtitle file
 *
 * tracks.json, not /api/media/<id>/tracks: that route keeps only English
 * subtitles for the watchparty UI, and offline the choice is the viewer's.
 */
object Watchparty {
    /** Where the web client's media drawer starts too: anime, movie, tv, playlists. */
    const val ROOT = "output/fmp4"

    fun catalog(context: Context, prefix: String): CatalogPage =
        CatalogPage.parse(Hadoku.get(context, "/watchparty/api/catalog?prefix=" + android.net.Uri.encode(prefix)))

    fun episode(context: Context, mediaId: String): EpisodeInfo = EpisodeInfo.parse(
        mediaId,
        index = Hadoku.get(context, "/watchparty/media/$mediaId/index.json"),
        tracks = Hadoku.get(context, "/watchparty/media/$mediaId/tracks.json"),
    )

    /** Just the re-ingest guard, for deciding whether a failed file may be resumed into the same episode. */
    fun contentToken(context: Context, mediaId: String): String =
        JSONObject(Hadoku.get(context, "/watchparty/media/$mediaId/index.json")).getString("content_token")
}

data class CatalogPage(val dirs: List<Dir>, val episodes: List<Entry>) {
    data class Dir(val name: String, val path: String)
    data class Entry(val mediaId: String, val title: String)

    companion object {
        /** Loose media files the catalog also lists (`x.mp4`) are not episodes in watchparty's layout; skipped. */
        private val LOOSE_FILE = Regex("""\.[A-Za-z0-9]+$""")

        fun parse(json: String): CatalogPage {
            val o = JSONObject(json)
            return CatalogPage(
                dirs = o.optJSONArray("dirs").objects().map { Dir(it.getString("name"), it.getString("path")) },
                episodes = o.optJSONArray("files").objects()
                    .filterNot { LOOSE_FILE.containsMatchIn(it.getString("id")) }
                    .map { Entry(it.getString("id"), it.getString("title")) },
            )
        }
    }
}

/** One remote file of an episode, relative to its media dir. [bytes] is from index.json; 0 when unknown (subtitles). */
data class RemoteFile(val path: String, val bytes: Long)

data class EpisodeInfo(
    val mediaId: String,
    val token: String,
    val durationMs: Long,
    val video: RemoteFile,
    val audio: List<Audio>,
    val subtitles: List<Subtitle>,
) {
    data class Audio(val lang: String, val label: String, val file: RemoteFile)
    data class Subtitle(val lang: String, val file: RemoteFile)

    companion object {
        const val VIDEO = "output_frag.mp4"
        // The same file-name rules watchparty's tracks route applies.
        private val AUDIO = Regex("""audio/audio_([a-z]{3})_\d+\.m4a""", RegexOption.IGNORE_CASE)
        private val SUBTITLE = Regex("""sub_([a-zA-Z][a-zA-Z0-9-]*)\.vtt""")

        /**
         * Sizes are the last fragment's end_byte + 1, as the MSE player maps
         * them; the files can carry a few KB more after the last fragment, so
         * these are close estimates, and DownloadManager reports the exact size.
         */
        fun parse(mediaId: String, index: String, tracks: String): EpisodeInfo {
            val idx = JSONObject(index)
            val trk = JSONObject(tracks)
            return EpisodeInfo(
                mediaId = mediaId,
                token = idx.getString("content_token"),
                durationMs = idx.optLong("duration_ms"),
                video = RemoteFile(VIDEO, endOf(idx)),
                audio = idx.optJSONArray("audio_tracks").objects().mapNotNull { a ->
                    val path = a.getString("path")
                    val lang = AUDIO.matchEntire(path)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                    Audio(lang, a.optString("label", lang), RemoteFile(path, endOf(a)))
                },
                subtitles = trk.optJSONArray("subtitles").strings().mapNotNull { name ->
                    SUBTITLE.matchEntire(name)?.let { Subtitle(it.groupValues[1].lowercase(), RemoteFile("subs/$name", 0)) }
                },
            )
        }

        private fun endOf(track: JSONObject): Long {
            val fragments = track.getJSONArray("fragments")
            return fragments.getJSONObject(fragments.length() - 1).getLong("end_byte") + 1
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
