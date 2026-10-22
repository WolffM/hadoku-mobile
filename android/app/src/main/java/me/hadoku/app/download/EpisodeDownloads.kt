package me.hadoku.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import me.hadoku.app.player.PlayerActivity

/**
 * Enqueues one file of an episode straight from watchparty's own media route,
 * into the directory the player reads (PlayerActivity.episodeDir).
 *
 * DownloadManager runs in the system's download provider, so it survives the
 * app being killed, retries across connectivity changes and reboots, and
 * resumes with Range + If-Match on the origin's ETag. A resumed request that
 * gets a 200 instead of a 206 fails with ERROR_CANNOT_RESUME, which is what
 * the resume spike watches for.
 *
 * The session is read from the WebView's cookie store at the moment of
 * enqueue and handed to DownloadManager as a header; the app never stores it
 * and never logs it. That is why this is native: the cookie is HttpOnly, so
 * no page script could pass it here.
 */
object EpisodeDownloads {
    const val SITE = "https://hadoku.me"

    /**
     * What watchparty's own background pre-buffer sends: the origin's media
     * queue serves the lowest buffer-ahead first, so a live party always
     * outranks a download. Only Range requests are queued, though; the first,
     * Range-less GET of a download bypasses the queue.
     */
    const val BUFFER_AHEAD_MS = "99999"

    /** [mediaId] is watchparty's catalog id: the episode's path under its media root, e.g. output/fmp4/anime/Show/show-s01e01. */
    fun url(mediaId: String, relPath: String) = "$SITE/watchparty/media/$mediaId/$relPath"

    /** Returns the DownloadManager id. Throws if there is no hadoku.me session to send. */
    fun enqueue(context: Context, mediaId: String, relPath: String, title: String): Long {
        val url = url(mediaId, relPath)
        val cookie = CookieManager.getInstance().getCookie(url)
        check(cookie != null && "hadoku_session=" in cookie) { "no hadoku.me session; sign in first" }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Cookie", cookie)
            .addRequestHeader("X-Buffer-Ahead-Ms", BUFFER_AHEAD_MS)
            // Request logs tell app sessions apart by this suffix; keep downloads in that set.
            .addRequestHeader("User-Agent", "HadokuApp/$version DownloadManager")
            .setDestinationInExternalFilesDir(context, PlayerActivity.EPISODES, "$mediaId/$relPath")
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }
}
