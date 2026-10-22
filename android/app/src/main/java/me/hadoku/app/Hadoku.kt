package me.hadoku.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native requests to hadoku.me, made as the signed-in visitor.
 *
 * The session is the WebView's own `hadoku_session` cookie, read from
 * CookieManager at the moment of each request and sent as a Cookie header:
 * the app never stores it and never logs it. HttpOnly hides it from page
 * JavaScript, not from the native cookie store.
 */
object Hadoku {
    const val SITE = "https://hadoku.me"

    /** The site answered 401/403: there is no session, or it expired. */
    class SignedOut : IOException("not signed in to hadoku.me")

    /** The Cookie header for [url], or null when there is no hadoku.me session. */
    fun session(url: String = SITE): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { "hadoku_session=" in it }

    /** Request logs tell app traffic apart by this suffix; the WebView sends the same one. */
    fun userAgent(context: Context, client: String): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "HadokuApp/$version $client"
    }

    /** GETs [path] on the site as text. Throws [SignedOut] on 401/403, IOException on any other failure. */
    fun get(context: Context, path: String): String {
        val url = SITE + path
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            session(url)?.let { conn.setRequestProperty("Cookie", it) }
            conn.setRequestProperty("User-Agent", userAgent(context, "Library"))
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                in 200..299 -> return conn.inputStream.bufferedReader().use { it.readText() }
                401, 403 -> throw SignedOut()
                else -> throw IOException("HTTP $code for $path")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Whether any network that claims internet is up. Airplane mode, no signal: false. */
    @JvmStatic
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
