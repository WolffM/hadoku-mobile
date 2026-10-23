package me.hadoku.app;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import com.getcapacitor.WebViewListener;

/**
 * The shell. Everything a visitor sees is the live site at capacitor.config.json's
 * server.url; this class exists so the two facts the spike has to prove show up in
 * logcat on every page load, and for nothing else yet:
 *
 *   1. the native layer can see the hadoku_session cookie the site set
 *      (HttpOnly hides it from page JavaScript, not from CookieManager);
 *   2. window.Capacitor exists inside a page served from hadoku.me, i.e. the
 *      bridge was injected into a remote document.
 *
 * Only PRESENCE is logged. The cookie value is a credential and never reaches
 * a log line.
 *
 * It also flushes the cookie store whenever the app leaves the foreground.
 * Chromium writes cookies to disk only periodically, and Capacitor flushes
 * only on its CapacitorCookies path, which stays disabled here; without this a
 * sign-in followed by Android killing the backgrounded app could be lost, and
 * the next launch would be signed out.
 *
 * And it owns Back: Capacitor core leaves Back to its optional App plugin, so
 * without this Back on any site left the app instead of returning to the page
 * before it. WebView history first; at the launcher, Android's own Back.
 *
 * And it keeps the launcher as home. The site's "home" links point at
 * hadoku.me/, which is the portfolio front page; in the app home is the
 * launcher, so a navigation to the site root goes back to the launcher entry
 * in history (or loads it, if there is none) instead.
 *
 * Under the page sits the app's bar, Hadoku and Library (ShellBar). Remote
 * mode has no offline page of its own, only a browser error, so a launch with
 * no network opens the Library, which works offline.
 */
public class MainActivity extends BridgeActivity {
    private static final String TAG = "HadokuShell";
    private static final String SITE = "https://hadoku.me";
    private static final String LAUNCHER = SITE + "/apps/";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge.setWebViewClient(new BridgeWebViewClient(bridge) {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isSiteRoot(request.getUrl())) {
                    goToLauncher(view);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });
        ShellBar.install(this);
        if (savedInstanceState == null && !Hadoku.online(this)) {
            ShellBar.openLibrary(this);
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = bridge.getWebView();
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
        this.bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) {
                String url = webView.getUrl();
                String cookies = CookieManager.getInstance().getCookie(SITE);
                boolean session = cookies != null && cookies.contains("hadoku_session=");
                Log.i(TAG, "page loaded url=" + url + " hadoku_session=" + (session ? "present" : "absent"));
                webView.evaluateJavascript(
                    "(function(){var c=window.Capacitor;return c?('bridge:'+(typeof c.isNativePlatform==='function'&&c.isNativePlatform())):'bridge:none'})()",
                    v -> Log.i(TAG, "page loaded url=" + url + " " + v)
                );
            }
        });
    }

    private static boolean isSiteRoot(Uri url) {
        String path = url.getPath();
        return "https".equals(url.getScheme()) && "hadoku.me".equals(url.getHost()) && (path == null || path.isEmpty() || path.equals("/"));
    }

    /** Back to the most recent launcher entry, so Back from there still leaves the app. */
    private static void goToLauncher(WebView webView) {
        WebBackForwardList history = webView.copyBackForwardList();
        for (int i = history.getCurrentIndex(); i >= 0; i--) {
            if (LAUNCHER.equals(history.getItemAtIndex(i).getUrl())) {
                webView.goBackOrForward(i - history.getCurrentIndex());
                return;
            }
        }
        webView.loadUrl(LAUNCHER);
    }

    @Override
    public void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }
}
