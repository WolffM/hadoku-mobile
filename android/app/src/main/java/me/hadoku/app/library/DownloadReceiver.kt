package me.hadoku.app.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * DownloadManager's ACTION_DOWNLOAD_COMPLETE (sent for success and failure
 * alike): brings the Library's records up to date even when the app is not
 * on screen, so a file that could not resume is restarted without waiting
 * for the viewer to open the Library. The broadcast carries nothing that is
 * trusted; it only triggers a refresh, which reads DownloadManager itself.
 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "library-refresh") {
            try {
                Downloads.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
