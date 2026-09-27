package mulin.tvdy;

import android.os.SystemClock;
import android.util.Log;

/**
 * Millisecond timeline for the cold-start window that the user actually
 * waits through: from {@code Application.onCreate} until ExoPlayer renders
 * its first frame and the splash fades out.
 * <p>
 * Everything on that path competes for the same few cores (WebView booting
 * douyin.com, the feed XHR, the first video segment decoding, and whatever
 * animation the splash is running), so "which step is slow" cannot be
 * guessed from the source - it has to be read off a device. Collect with:
 * <pre>adb logcat -c &amp;&amp; adb logcat -s StartupTrace:I</pre>
 * <p>
 * Pure instrumentation: no behaviour, no state beyond one baseline timestamp.
 */
public final class StartupTrace {

    private static final String TAG = "StartupTrace";

    /**
     * Set on first touch, which happens in {@link TvdyApplication#onCreate}.
     * {@code Process.getStartUptimeMillis()} would be more accurate but is
     * API 24, and this module's minSdk is 21.
     */
    private static long originMs = 0;

    private StartupTrace() {
    }

    /** Records the timeline's zero point. Idempotent. */
    public static void start() {
        if (originMs == 0) {
            originMs = SystemClock.uptimeMillis();
        }
    }

    /** Logs {@code +<elapsed>ms <label>} relative to {@link #start()}. */
    public static void mark(String label) {
        long now = SystemClock.uptimeMillis();
        if (originMs == 0) {
            originMs = now;
        }
        Log.i(TAG, "+" + (now - originMs) + "ms " + label);
    }

    /** Logs a duration that was measured separately, keeping it on the same timeline. */
    public static void markDelta(String label, long elapsedMs) {
        mark(label + " (" + elapsedMs + "ms)");
    }
}
