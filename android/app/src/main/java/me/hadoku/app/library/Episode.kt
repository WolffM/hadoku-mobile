package me.hadoku.app.library

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One episode in the Library: what was chosen, where each file's download
 * stands, and where the viewer stopped. It lives as `episode.json` in the
 * episode's own directory, beside the files it describes, so an episode is
 * self-contained: delete is one directory, space used is one walk, and
 * nothing can say an episode exists whose files are gone.
 *
 * [token] is index.json's content_token (the video's sha256) at enqueue time.
 * A file may only be resumed or re-fetched into this episode while the server
 * still has the same token; otherwise the bytes would be spliced from two
 * encodes.
 */
data class Episode(
    val mediaId: String,
    val title: String,
    val series: String,
    val token: String,
    val durationMs: Long,
    val addedAt: Long,
    val files: List<FileState>,
    val positionMs: Long = 0,
    /** Set when the episode as a whole can't finish, e.g. it was re-encoded on the server. */
    val problem: String? = null,
) {
    data class FileState(
        val path: String,
        val bytes: Long,
        val downloadId: Long,
        val done: Boolean = false,
        val failure: String? = null,
    )

    val ready get() = problem == null && files.all { it.done }
    val failed get() = problem != null || files.any { it.failure != null }

    fun toJson(): String = JSONObject()
        .put("mediaId", mediaId)
        .put("title", title)
        .put("series", series)
        .put("token", token)
        .put("durationMs", durationMs)
        .put("addedAt", addedAt)
        .put("positionMs", positionMs)
        .put("problem", problem ?: JSONObject.NULL)
        .put("files", JSONArray(files.map {
            JSONObject()
                .put("path", it.path)
                .put("bytes", it.bytes)
                .put("downloadId", it.downloadId)
                .put("done", it.done)
                .put("failure", it.failure ?: JSONObject.NULL)
        }))
        .toString(2)

    companion object {
        fun fromJson(json: String): Episode {
            val o = JSONObject(json)
            return Episode(
                mediaId = o.getString("mediaId"),
                title = o.getString("title"),
                series = o.getString("series"),
                token = o.getString("token"),
                durationMs = o.getLong("durationMs"),
                addedAt = o.getLong("addedAt"),
                positionMs = o.optLong("positionMs"),
                problem = o.stringOrNull("problem"),
                files = o.getJSONArray("files").objects().map {
                    FileState(
                        path = it.getString("path"),
                        bytes = it.getLong("bytes"),
                        downloadId = it.getLong("downloadId"),
                        done = it.getBoolean("done"),
                        failure = it.stringOrNull("failure"),
                    )
                },
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}

/**
 * The Library on disk: `<root>/<mediaId>/episode.json` for each episode, the
 * downloaded files beside it. Writes go through a temp file and a rename, and
 * every read-modify-write holds one lock, because the download receiver, the
 * Library screen and the player all update the same records.
 */
class LibraryStore(private val root: File) {
    fun dir(mediaId: String) = File(root, mediaId)

    fun all(): List<Episode> = synchronized(LOCK) {
        root.walk().filter { it.name == MANIFEST }.map { Episode.fromJson(it.readText()) }
            .sortedByDescending { it.addedAt }.toList()
    }

    fun get(mediaId: String): Episode? = synchronized(LOCK) {
        File(dir(mediaId), MANIFEST).takeIf { it.isFile }?.let { Episode.fromJson(it.readText()) }
    }

    fun put(episode: Episode) = synchronized(LOCK) {
        val dir = dir(episode.mediaId).apply { mkdirs() }
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(episode.toJson())
        check(tmp.renameTo(File(dir, MANIFEST))) { "could not write ${dir}/$MANIFEST" }
    }

    /** Applies [change] to the stored episode; null (and nothing written) if it is not in the Library. */
    fun update(mediaId: String, change: (Episode) -> Episode): Episode? = synchronized(LOCK) {
        get(mediaId)?.let(change)?.also(::put)
    }

    /** Removes the episode's directory, then any parent directories that leaves empty. */
    fun delete(mediaId: String) = synchronized(LOCK) {
        val dir = dir(mediaId)
        check(!dir.exists() || dir.deleteRecursively()) { "could not delete $dir" }
        var parent = dir.parentFile
        while (parent != null && parent != root && parent.list()?.isEmpty() == true) {
            parent.delete()
            parent = parent.parentFile
        }
    }

    /** Bytes on disk under the Library, partial downloads included. */
    fun bytesUsed(): Long = root.walk().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MANIFEST = "episode.json"
        private val LOCK = Any()
    }
}
