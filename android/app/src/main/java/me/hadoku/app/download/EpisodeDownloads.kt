package me.hadoku.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import me.hadoku.app.Hadoku
import me.hadoku.app.player.PlayerActivity

/**
 * Enqueues one file of an episode straight from watchparty's own media route,
 * into the directory the player reads (PlayerActivity.episodeDir).
 *
 * DownloadManager runs in the system's download provider, so it survives the
 * app being killed, retries across connectivity changes and reboots, and
 * resumes with Range + If-Match on the origin's ETag. A resumed request that
 * gets a 200 instead of a 206 fails with ERROR_CANNOT_RESUME. The download
 * spike (DownloadResumeTest) showed resumes through the edge get their 206.
 *
 * The session is read from the WebView's cookie store at the moment of
 * enqueue and handed to DownloadManager as a header; the app never stores it
 * and never logs it. That is why this is native: the cookie is HttpOnly, so
 * no page script could pass it here.
 */
object EpisodeDownloads {
    /**
     * What watchparty's own background pre-buffer sends: the origin's media
     * queue serves the lowest buffer-ahead first, so a live party always
     * outranks a download. Only Range requests are queued, though; the first,
     * Range-less GET of a download bypasses the queue.
     */
    const val BUFFER_AHEAD_MS = "99999"

    /** [mediaId] is watchparty's catalog id: the episode's path under its media root, e.g. output/fmp4/anime/Show/show-s01e01. */
    fun url(mediaId: String, relPath: String) = "${Hadoku.SITE}/watchparty/media/$mediaId/$relPath"

    /**
     * Returns the DownloadManager id. Throws [Hadoku.SignedOut] if there is no
     * session to send. Any file already at the destination is replaced:
     * DownloadManager refuses to overwrite one.
     */
    fun enqueue(context: Context, mediaId: String, relPath: String, title: String): Long {
        val url = url(mediaId, relPath)
        val cookie = Hadoku.session(url) ?: throw Hadoku.SignedOut()
        File(PlayerActivity.episodeDir(context, mediaId), relPath).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Cookie", cookie)
            .addRequestHeader("X-Buffer-Ahead-Ms", BUFFER_AHEAD_MS)
            .addRequestHeader("User-Agent", Hadoku.userAgent(context, "DownloadManager"))
            .setDestinationInExternalFilesDir(context, PlayerActivity.EPISODES, "$mediaId/$relPath")
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }
}
