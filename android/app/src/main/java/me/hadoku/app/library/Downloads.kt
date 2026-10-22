package me.hadoku.app.library

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import me.hadoku.app.Hadoku
import me.hadoku.app.download.EpisodeDownloads
import me.hadoku.app.player.PlayerActivity

/**
 * The Library's downloads: one DownloadManager request per file of an
 * episode (video, each chosen audio language, each chosen subtitle), the
 * episode's record kept in step with them.
 *
 * [refresh] is the one place a record learns what DownloadManager did. The
 * Library screen calls it while visible and DownloadReceiver calls it when a
 * file finishes, so every path converges on the same code. It does network
 * work only to restart a file DownloadManager could not resume, so it must
 * run off the main thread.
 */
object Downloads {
    /** Left free after a download, so a full episode never fills the phone. */
    const val RESERVE_BYTES = 256L * 1024 * 1024

    /** One episode's state for the Library screen. */
    data class Row(val episode: Episode, val bytesDone: Long, val bytesTotal: Long, val waitingForNetwork: Boolean)

    fun store(context: Context) = LibraryStore(context.getExternalFilesDir(PlayerActivity.EPISODES)!!)

    fun freeBytes(context: Context): Long = StatFs(context.getExternalFilesDir(null)!!.path).availableBytes

    /** Enqueues every chosen file and records the episode. Throws [Hadoku.SignedOut] without a session. */
    fun start(
        context: Context,
        info: EpisodeInfo,
        title: String,
        series: String,
        audio: List<EpisodeInfo.Audio>,
        subtitles: List<EpisodeInfo.Subtitle>,
    ): Episode {
        // Up front, so a missing session can't leave half the files enqueued with no record of them.
        Hadoku.session() ?: throw Hadoku.SignedOut()
        val wanted =listOf(info.video) + audio.map { it.file } + subtitles.map { it.file }
        val episode = Episode(
            mediaId = info.mediaId,
            title = title,
            series = series,
            token = info.token,
            durationMs = info.durationMs,
            addedAt = System.currentTimeMillis(),
            files = wanted.map { Episode.FileState(it.path, it.bytes, enqueue(context, info.mediaId, it.path, title)) },
        )
        store(context).put(episode)
        return episode
    }

    fun refresh(context: Context): List<Row> {
        val dm = context.getSystemService(DownloadManager::class.java)
        val store = store(context)
        return store.all().map { stored ->
            var waiting = false
            var progress = 0L
            val files = stored.files.map { f ->
                if (f.done || f.failure != null) return@map f
                val q = query(dm, f.downloadId)
                when (q?.status) {
                    null -> f.copy(failure = "The download was cancelled")
                    DownloadManager.STATUS_SUCCESSFUL ->
                        f.copy(done = true, bytes = File(store.dir(stored.mediaId), f.path).length())
                    DownloadManager.STATUS_FAILED ->
                        if (q.reason == DownloadManager.ERROR_CANNOT_RESUME) f.copy(failure = CANNOT_RESUME)
                        else f.copy(failure = describe(q.reason))
                    else -> {
                        if (q.status == DownloadManager.STATUS_PAUSED) waiting = true
                        progress += q.bytes
                        f.copy(bytes = maxOf(f.bytes, q.total))
                    }
                }
            }
            var episode = if (files != stored.files) store.update(stored.mediaId) { it.copy(files = files) } ?: stored else stored
            // A resume that got a 200 instead of a 206. Restart that file,
            // but only into the same encode.
            if (episode.files.any { it.failure == CANNOT_RESUME }) episode = retry(context, episode.mediaId) ?: episode
            Row(
                episode = episode,
                bytesDone = episode.files.filter { it.done }.sumOf { it.bytes } + progress,
                bytesTotal = episode.files.sumOf { it.bytes },
                waitingForNetwork = waiting,
            )
        }
    }

    /**
     * Re-enqueues every failed file, after checking the server still has the
     * same encode. Returns the updated episode, or null if it is gone.
     * Network work: call off the main thread.
     */
    fun retry(context: Context, mediaId: String): Episode? {
        val store = store(context)
        val episode = store.get(mediaId) ?: return null
        val token = try {
            Watchparty.contentToken(context, mediaId)
        } catch (e: IOException) {
            return store.update(mediaId) { it.copy(files = it.files.map { f -> if (f.failure == CANNOT_RESUME) f.copy(failure = failureOf(e)) else f }) }
        }
        if (token != episode.token) {
            return store.update(mediaId) { it.copy(problem = "This episode changed on the server. Delete it and download it again.") }
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        return store.update(mediaId) {
            it.copy(files = it.files.map { f ->
                if (f.failure == null) return@map f
                dm.remove(f.downloadId)
                try {
                    f.copy(downloadId = enqueue(context, mediaId, f.path, it.title), failure = null, done = false)
                } catch (e: IOException) {
                    f.copy(failure = failureOf(e))
                }
            })
        }
    }

    /** Cancels whatever is still downloading and removes the episode's files and record. */
    fun delete(context: Context, mediaId: String) {
        val store = store(context)
        store.get(mediaId)?.files?.filterNot { it.done }?.forEach {
            context.getSystemService(DownloadManager::class.java).remove(it.downloadId)
        }
        store.delete(mediaId)
    }

    private fun enqueue(context: Context, mediaId: String, path: String, title: String): Long =
        EpisodeDownloads.enqueue(context, mediaId, path, title)

    private const val CANNOT_RESUME = "The server could not resume this file"

    private fun failureOf(e: IOException) =
        if (e is Hadoku.SignedOut) SIGNED_OUT else "Couldn't reach hadoku.me (${e.message})"

    private const val SIGNED_OUT = "Signed out of hadoku.me. Sign in on the Hadoku tab, then retry."

    private fun describe(reason: Int): String = when (reason) {
        401, 403 -> SIGNED_OUT
        404 -> "No longer on the server"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough space on the phone"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage is not available"
        in 100..599 -> "The server answered HTTP $reason"
        else -> "Download error $reason"
    }

    private data class Query(val status: Int, val reason: Int, val bytes: Long, val total: Long)

    private fun query(dm: DownloadManager, id: Long): Query? =
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            Query(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
}
