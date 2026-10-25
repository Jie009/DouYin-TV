package mulin.tvdy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Pays the one-time Chromium/WebView cold-start cost as early as possible
 * (Application.onCreate) so the feed pump in {@link mulin.tvdy.player.PlayerActivity}
 * does not eat that time on the critical path to the first video.
 * <p>
 * The probe WebView is deliberately <em>kept alive</em> in {@link #probeRef}
 * rather than destroyed. Matching WebViews in one process share a single
 * Chromium renderer, and tearing down the only live WebView can shut that
 * renderer back down - which would make the real pump WebView pay the whole
 * start-up cost a second time, i.e. exactly the opposite of the point here.
 * The field is never displayed and never navigated, so the cost of holding
 * it is a few MB and no rendering.
 */
public final class WebViewPumpWarmup {

    private static final String TAG = "WebViewWarmup";

    private static boolean warmed = false;

    /** Held so the warmed renderer stays alive; never shown, never navigated. */
    private static WebView probeRef;

    private WebViewPumpWarmup() {
    }

    public static synchronized void warm(Context context) {
        if (warmed) return;
        warmed = true;
        Context app = context.getApplicationContext();
        CookieManager.getInstance().setAcceptCookie(true);
        new Handler(Looper.getMainLooper()).post(() -> {
            long startedAt = SystemClock.uptimeMillis();
            try {
                WebView probe = new WebView(app);
                WebSettings settings = probe.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                probeRef = probe;
                StartupTrace.markDelta("WebView warmup done", SystemClock.uptimeMillis() - startedAt);

                // Deliberately no loadUrl() here. Issuing a throwaway request
                // to douyin.com to pre-settle DNS/TCP/TLS was tried and
                // measured: the pump's loadUrl -> onPageStarted gap went from
                // 994/1349ms to 866/926/893ms, which is inside run-to-run
                // noise, and first-frame times (4968/6148/5831ms) were
                // indistinguishable from the 5016ms baseline median. The reason
                // is structural: this probe only reaches loadUrl at ~+180ms
                // while the pump issues its own request at ~+394ms, and
                // Chromium picks the connection at request time, so the pump
                // never reuses a socket this probe opens. Warming cannot
                // outrun the thing it is warming for.
                Log.d(TAG, "WebView engine warmed (kept alive)");
            } catch (Exception e) {
                StartupTrace.mark("WebView warmup FAILED");
                Log.w(TAG, "WebView warmup failed", e);
            }
        });
    }
}
