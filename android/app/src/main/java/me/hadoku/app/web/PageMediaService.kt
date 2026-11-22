package me.hadoku.app.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import me.hadoku.app.MainActivity

/**
 * Keeps the app alive while a page in the WebView plays sound, which is what
 * Chrome does for a playing tab: a foreground service of type mediaPlayback,
 * with a media notification. Without one, the screen going off leaves the
 * process cached and Android's freezer stops it (and the page's audio)
 * about a minute later.
 *
 * While the page plays it also holds a partial wake lock and a Wi-Fi lock, as
 * ExoPlayer's WAKE_MODE_NETWORK does for streamed media: a hadoku player reads
 * its file in HTTP range chunks as it goes, so the radio must stay up between
 * them with the screen off.
 *
 * A pause keeps the service (and its notification, now offering play) for
 * GRACE_MS before it stops. A player that moves to its next track, or retries
 * a dropped stream, pauses for a moment, and a foreground service cannot be
 * started again from the background (ForegroundServiceStartNotAllowedException),
 * so stopping on every pause would end the night at the first track change.
 * A navigation or the activity's end stops it at once.
 *
 * All state lives on the main thread, where WebView delivers the page's messages.
 */
class PageMediaService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock
    private val stopAfterGrace = Runnable { end("paused for ${GRACE_MS / 1000} s") }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing on hadoku.me", NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSession(this, "HadokuPageMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command("play")
                override fun onPause() = command("pause")
                override fun onStop() = command("pause")
            })
            setSessionActivity(openApp())
            isActive = true
        }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hadoku:page-media")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION") // FULL_HIGH_PERF is what ExoPlayer's network wake mode takes too.
        val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, "hadoku:page-media")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            // A notification button outliving the service it belonged to (Android restarted it bare).
            intent?.action != null && running == null -> stopSelf()
            intent?.action == ACTION_PLAY -> command("play")
            intent?.action == ACTION_PAUSE -> command("pause")
            else -> {
                // Promised to startForegroundService, so kept even when the page left before we got here.
                ServiceCompat.startForeground(this, NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                if (page == null) {
                    end("navigation before start")
                } else {
                    if (running == null) Log.i(TAG, "page media keep-alive started reason=playing title=$title")
                    running = this
                    refresh(null)
                }
            }
        }
        // Nothing to restart into: after a kill the page that was playing is gone.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(stopAfterGrace)
        if (running === this) running = null
        locks(false)
        session.release()
        super.onDestroy()
    }

    /** Brings the notification, the session and the locks in line with the page; logs the change as [reason]. */
    private fun refresh(reason: String?) {
        if (reason != null) Log.i(TAG, "page media keep-alive $reason title=$title")
        main.removeCallbacks(stopAfterGrace)
        if (!playing) main.postDelayed(stopAfterGrace, GRACE_MS)
        locks(playing)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { "hadoku.me" }).build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun end(reason: String) {
        Log.i(TAG, "page media keep-alive stopped reason=$reason")
        running = null
        locks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun locks(hold: Boolean) {
        if (hold) {
            if (!wake.isHeld) wake.acquire()
            if (!wifi.isHeld) wifi.acquire()
        } else {
            if (wake.isHeld) wake.release()
            if (wifi.isHeld) wifi.release()
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else legacyBuilder()
        val action = if (playing) {
            action(androidx.media3.session.R.drawable.media3_icon_pause, "Pause", ACTION_PAUSE)
        } else {
            action(androidx.media3.session.R.drawable.media3_icon_play, "Play", ACTION_PLAY)
        }
        return builder
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(title.ifEmpty { "hadoku.me" })
            .setContentText(if (playing) "Playing in Hadoku" else "Paused")
            .setContentIntent(openApp())
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun action(icon: Int, label: String, what: String) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, control(what)).build()

    @Suppress("DEPRECATION") // Before channels (API 26) a builder takes none.
    private fun legacyBuilder() = Notification.Builder(this)

    private fun control(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, PageMediaService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val TAG = "HadokuShell"
        private const val CHANNEL = "page-media"
        private const val NOTIFICATION = 0x4d45
        private const val ACTION_PLAY = "me.hadoku.app.web.PLAY"
        private const val ACTION_PAUSE = "me.hadoku.app.web.PAUSE"
        const val GRACE_MS = 120_000L

        private var playing = false
        private var title = ""
        private var page: JavaScriptReplyProxy? = null
        private var running: PageMediaService? = null

        /** The page's latest report, from the frame that [reply] answers. */
        fun report(context: Context, report: PageMediaReport, reply: JavaScriptReplyProxy) {
            val was = playing
            playing = report.playing
            title = report.title
            page = reply
            val service = running
            when {
                service != null -> service.refresh(if (playing == was) "title" else if (playing) "resumed" else "paused")
                playing -> start(context)
            }
        }

        /** Ends the keep-alive now, whatever the page last said. */
        @JvmStatic
        fun stop(reason: String) {
            playing = false
            page = null
            running?.end(reason)
        }

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PageMediaService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the page began to play with the app
                // already in the background, which only a grace period that outlived it would allow.
                Log.w(TAG, "page media keep-alive refused: ${e.message}")
            }
        }

        private fun command(what: String) {
            Log.i(TAG, "page media keep-alive control=$what")
            page?.postMessage(what)
        }
    }
}
