package me.hadoku.app.download

import android.app.DownloadManager
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import me.hadoku.app.LiveProduction
import me.hadoku.app.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download spike's go/no-go: DownloadManager fetches a real episode file
 * through the edge with the session cookie, loses the network (airplane mode)
 * mid-file, and finishes with bytes identical to the origin's.
 *
 * Finishing after an interruption IS the 206 proof: on a resumed request
 * DownloadManager sends Range + If-Match and fails the download with
 * ERROR_CANNOT_RESUME if the answer is a 200. The cache probe runs only
 * AFTER the download, so it cannot warm Cloudflare's cache for a cold run.
 *
 * Needs a signed-in emulator (`pnpm emulator:sign-in`) and the expected size
 * and sha256 of the origin file; `pnpm test:download-resume` supplies both.
 * Not part of `pnpm test:device`: it moves hundreds of MB through production.
 */
@LiveProduction
@RunWith(AndroidJUnit4::class)
class DownloadResumeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val dm = context.getSystemService(DownloadManager::class.java)

    @Test
    fun resumesThroughTheEdgeAfterLosingTheNetwork() {
        val mediaId = args.getString("mediaId") ?: "output/fmp4/anime/Frieren/frieren-s01e01"
        val relPath = args.getString("file") ?: "output_frag.mp4"
        val size = requireNotNull(args.getString("size")) { "-e size <bytes> is required" }.toLong()
        val sha256 = requireNotNull(args.getString("sha256")) { "-e sha256 <hex> is required" }
        val interruptions = args.getString("interruptions")?.toInt() ?: 2
        val session = CookieManager.getInstance().getCookie(EpisodeDownloads.SITE)
        assertTrue("no hadoku.me session on this device; run pnpm emulator:sign-in", session?.contains("hadoku_session=") == true)

        val dest = File(PlayerActivity.episodeDir(context, mediaId), relPath).apply { delete() }
        val started = SystemClock.elapsedRealtime()
        val id = EpisodeDownloads.enqueue(context, mediaId, relPath, "spike: $mediaId/$relPath")
        try {
            for (i in 1..interruptions) {
                val at = size * i / (interruptions + 1)
                waitFor(id, "$at bytes") { it.bytes >= at }
                airplane(true)
                val paused = waitFor(id, "paused without network") {
                    check(it.status != DownloadManager.STATUS_SUCCESSFUL) { "finished before interruption $i landed; use a bigger file" }
                    it.status == DownloadManager.STATUS_PAUSED
                }
                Log.i(TAG, "interruption $i: paused at ${paused.bytes}/$size reason=${paused.reason}")
                SystemClock.sleep(5_000)
                airplane(false)
                val resumed = waitFor(id, "bytes beyond ${paused.bytes} after reconnecting") { it.bytes > paused.bytes }
                Log.i(TAG, "interruption $i: resumed, now ${resumed.bytes}/$size")
            }
            waitFor(id, "download complete", timeoutMs = 30 * 60_000L) { it.status == DownloadManager.STATUS_SUCCESSFUL }
        } finally {
            airplane(false)
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1_000.0
        Log.i(TAG, "complete: $size bytes in ${"%.1f".format(seconds)} s with $interruptions interruptions")

        assertEquals("size of $dest", size, dest.length())
        assertEquals("sha256 of $dest", sha256, sha256(dest))
        probeCache(EpisodeDownloads.url(mediaId, relPath), session!!, size)
    }

    private data class Progress(val status: Int, val reason: Int, val bytes: Long)

    private fun progress(id: Long): Progress =
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            check(c.moveToFirst()) { "download $id vanished" }
            Progress(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            )
        }

    private fun waitFor(id: Long, what: String, timeoutMs: Long = 5 * 60_000L, condition: (Progress) -> Boolean): Progress {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val p = progress(id)
            // ERROR_CANNOT_RESUME (1008) here means a resumed request got a 200: the edge hazard.
            if (p.status == DownloadManager.STATUS_FAILED) throw AssertionError("download failed waiting for $what: reason=${p.reason} at ${p.bytes} bytes")
            if (condition(p)) return p
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what: $p")
            SystemClock.sleep(100)
        }
    }

    private fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    /**
     * After the fact, what the edge does with a Range request for this file
     * now: whether Cloudflare holds it (cf-cache-status) and whether a mid-file
     * Range still gets a 206. Headers only; the cookie is never logged.
     */
    private fun probeCache(url: String, session: String, size: Long) {
        val mid = size / 2
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Cookie", session)
            conn.setRequestProperty("Range", "bytes=$mid-${mid + 1023}")
            conn.setRequestProperty("X-Buffer-Ahead-Ms", EpisodeDownloads.BUFFER_AHEAD_MS)
            val headers = listOf("cf-cache-status", "age", "content-range", "content-length", "etag", "accept-ranges", "cache-control")
                .joinToString(" ") { "$it=${conn.getHeaderField(it)}" }
            Log.i(TAG, "probe after: ${conn.responseCode} $headers")
            assertEquals("mid-file Range after the download", 206, conn.responseCode)
        } finally {
            conn.disconnect()
        }
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
        const val TAG = "HadokuDownloadTest"
    }
}
