package me.hadoku.app.web

import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Tells the shell when a page in the WebView is playing sound, so
 * PageMediaService can keep the app alive the way Chrome keeps itself alive
 * for a playing tab. Without it the process is cached as soon as the screen
 * goes off and the freezer stops it, audio and all, about a minute later.
 *
 * A Capacitor plugin only for its place in the start-up order: Bridge loads
 * plugins before the first page, so the script below is in every document,
 * the launcher included. It has no methods for pages to call. A navigation
 * stops the keep-alive from MainActivity's WebViewListener, not from here:
 * Bridge.Builder replaces the bridge's listener list after plugins load, so a
 * listener a plugin adds in load() is silently dropped.
 *
 * The page side is a document-start script on hadoku.me only. It hooks
 * HTMLMediaElement.play (a detached `new Audio()`, which is how a music player
 * plays, never reaches a document listener) and listens in the capture phase
 * for media in the document, then reports `{playing, title}` whenever the
 * answer to "is anything audible playing" changes. Muted or silent media
 * (a looping art video) does not count. The title is the page's
 * navigator.mediaSession title where that exists, else document.title:
 * Android WebView has no Media Session API (no MediaMetadata at all).
 *
 * The report travels over a WebMessageListener whose allowed origin is
 * hadoku.me, not a JavascriptInterface, which every frame of every origin
 * could call. Only the main frame is listened to. The way back, for the
 * notification's play and pause, is the reply proxy of that frame: "pause"
 * pauses what is audible and remembers it, "play" plays that again.
 */
@CapacitorPlugin(name = "HadokuPageMedia")
class PageMediaPlugin : Plugin() {
    override fun load() {
        val webView = bridge.webView
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            Log.w(TAG, "page media keep-alive unavailable: this WebView has no message listener or document-start script")
            return
        }
        val app = context.applicationContext
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(ORIGIN)) { _, message, _, isMainFrame, reply ->
            if (!isMainFrame) return@addWebMessageListener
            val report = PageMediaReport.parse(message.data) ?: return@addWebMessageListener
            PageMediaService.report(app, report, reply)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(ORIGIN))
    }

    override fun handleOnDestroy() = PageMediaService.stop("activity destroyed")

    companion object {
        private const val TAG = "HadokuShell"
        const val ORIGIN = "https://hadoku.me"
        const val BRIDGE_NAME = "HadokuMedia"

        val SCRIPT = """
            (() => {
              const port = window.$BRIDGE_NAME;
              if (!port || window !== window.top) return;
              const seen = new WeakSet();
              const active = new Set();
              let paused = [];
              let last = '';
              const audible = (m) => !m.paused && !m.ended && !m.muted && m.volume > 0;
              const title = () => {
                const meta = navigator.mediaSession && navigator.mediaSession.metadata;
                return String((meta && meta.title) || document.title || '').slice(0, 200);
              };
              const report = (e) => {
                const m = e.currentTarget;
                if (audible(m)) active.add(m); else active.delete(m);
                const message = JSON.stringify({ playing: active.size > 0, title: title() });
                if (message !== last) { last = message; port.postMessage(message); }
              };
              const watch = (m) => {
                if (seen.has(m)) return;
                seen.add(m);
                for (const type of ['play', 'playing', 'pause', 'ended', 'emptied', 'volumechange']) m.addEventListener(type, report);
              };
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () { watch(this); return play.apply(this, arguments); };
              document.addEventListener('play', (e) => {
                if (e.target instanceof HTMLMediaElement && !seen.has(e.target)) { watch(e.target); report({ currentTarget: e.target }); }
              }, true);
              port.addEventListener('message', (e) => {
                if (e.data === 'pause') { paused = [...active]; paused.forEach((m) => m.pause()); }
                if (e.data === 'play') paused.splice(0).forEach((m) => m.play().catch((err) => console.warn('HadokuMedia play:', err)));
              });
            })();
        """.trimIndent()
    }
}
