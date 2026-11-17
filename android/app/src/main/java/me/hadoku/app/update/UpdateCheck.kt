package me.hadoku.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import me.hadoku.app.Hadoku
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Tells the visitor when a newer release exists, and takes them to it.
 *
 * The app is sideloaded, so nothing else does. A release is a GitHub Release
 * on this repo (build-apk.yml creates it with one `hadoku-vX.Y.Z.apk`); the
 * check reads the public `releases/latest` endpoint, compares its tag with the
 * installed versionName, and if it is newer offers "Update", which opens the
 * APK's download link in the browser: the same path the app was installed by.
 *
 * At most one check every [INTERVAL_MS], counted from the last check that got
 * an answer, so "Later" asks again only after that. A development build
 * (0.0.0-dev) never checks: it is not a release and every release is newer.
 */
object UpdateCheck {
    private const val TAG = "HadokuUpdate"
    private const val LATEST = "https://api.github.com/repos/WolffM/hadoku-mobile/releases/latest"
    private const val PREFS = "update-check"
    private const val CHECKED_AT = "checkedAt"
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(val version: String, val apkUrl: String, val pageUrl: String) {
        companion object {
            /** A `releases/latest` body; null when it is not a plain vX.Y.Z release with an APK. */
            fun parse(json: String): Release? {
                val release = JSONObject(json)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
                val version = release.optString("tag_name").removePrefix("v")
                if (parse3(version) == null) return null
                val assets = release.optJSONArray("assets") ?: return null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
                return Release(version, apk.getString("browser_download_url"), release.optString("html_url"))
            }
        }
    }

    private fun parse3(version: String): List<Int>? =
        Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(version)?.groupValues?.drop(1)?.map { it.toInt() }

    /** Whether [latest] is a newer X.Y.Z than [installed]; never for a non-release [installed] such as 0.0.0-dev. */
    fun isNewer(latest: String, installed: String): Boolean {
        val a = parse3(latest) ?: return false
        val b = parse3(installed) ?: return false
        return compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }) > 0
    }

    /** Checks in the background if one is due, and shows the prompt on [activity] if a newer release exists. */
    @JvmStatic
    fun maybeCheck(activity: Activity) {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: return
        if (parse3(installed) == null) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(CHECKED_AT, 0) < INTERVAL_MS) return
        if (!Hadoku.online(activity)) return
        val app = activity.applicationContext
        thread(name = "update-check") {
            val release = try {
                fetchLatest(app)
            } catch (e: IOException) {
                Log.w(TAG, "update check failed: ${e.message}")
                return@thread
            }
            prefs.edit().putLong(CHECKED_AT, now).apply()
            Log.i(TAG, "installed=$installed latest=${release?.version ?: "none"}")
            if (release == null || !isNewer(release.version, installed)) return@thread
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) prompt(activity, installed, release)
            }
        }
    }

    private fun fetchLatest(context: Context): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", Hadoku.userAgent(context, "UpdateCheck"))
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 404) return null // no release yet
            if (code !in 200..299) throw IOException("HTTP $code from releases/latest")
            return Release.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun prompt(activity: Activity, installed: String, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Hadoku ${release.version} is out (you have $installed). Download it, then open the file to install it over this one.")
            .setPositiveButton("Update") { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
