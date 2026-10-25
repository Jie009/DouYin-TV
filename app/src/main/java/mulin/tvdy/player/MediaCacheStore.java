package mulin.tvdy.player;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * Process-wide ExoPlayer media cache, keyed by the douyin CDN URLs the feed
 * handed us.
 * <p>
 * The cold-start budget the snapshot cannot cover is the network itself: even
 * with a signed URL already in hand, the first frame still waits on a CDN
 * connect plus the first segments. Measured on the TV that is ~1.4s of the
 * ~1.6s ExoPlayer span. Caching the bytes is the only way to remove it, and it
 * is what makes a true cold start land near a second.
 * <p>
 * Note what is cached is the <em>response body per URL</em>. Douyin's CDN links
 * are signed and short-lived, so a later launch usually presents a
 * freshly-signed URL for the same media and would miss a pure URL-keyed cache.
 * That is handled in {@link #canonicalKey}: the signature lives in the query
 * string, while the media identity lives in the path, so requests are rewritten
 * to a stripped, stable key before reaching the cache.
 */
@UnstableApi
public final class MediaCacheStore {

    private static final String TAG = "MediaCache";

    private static final String DIR = "media-cache";
    /**
     * Cap chosen for a TV with ~1.9GB RAM and modest internal storage: enough
     * for the first video plus its neighbours at 720p, small enough that the
     * evictor never has to fight the rest of the app for disk.
     */
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private static SimpleCache cache;

    /**
     * Diagnostic A/B switch, set from {@code am start --ez tvdy_no_media_cache
     * true}. Lets a single video be compared with and without the cache on the
     * same device, which is the only way to tell a cache-content problem from
     * anything else on screen.
     */
    private static volatile boolean disabled = false;

    private MediaCacheStore() {
    }

    public static void setDisabled(boolean value) {
        disabled = value;
    }

    public static boolean isDisabled() {
        return disabled;
    }

    /** Lazily built; the first caller may be a background preload thread. */
    @Nullable
    public static synchronized SimpleCache get(Context context) {
        if (disabled) return null;
        if (cache != null) return cache;
        try {
            File dir = new File(context.getApplicationContext().getCacheDir(), DIR);
            StandaloneDatabaseProvider dbProvider =
                    new StandaloneDatabaseProvider(context.getApplicationContext());
            cache = new SimpleCache(
                    dir,
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    dbProvider);
            Log.d(TAG, "media cache ready at " + dir);
        } catch (Exception e) {
            // A missing cache must never break playback, only slow the first frame.
            Log.w(TAG, "media cache unavailable", e);
        }
        return cache;
    }

    /**
     * A cache key that survives re-signing.
     * <p>
     * Observed URL shape (from a captured feed):
     * <pre>
     * https://v5-dy-ov-experiment.zjcdn.com/4281c66192517c9342268c58626c8324/6ad77f21
     *     /video/tos/cn/tos-cn-vd-0026/8be43d39793b4eb5ba644bae1d70e5e3/media-video-avc1/?a=...
     * </pre>
     * The two leading path segments after the host are per-issue signing
     * material and change when the feed is re-fetched, as does the whole query
     * string ({@code dy_q}, {@code l}, {@code btag}, signatures). Everything
     * from {@code /video/tos/} onward identifies the actual content-addressed
     * file, so that is what the key is built from.
     * <p>
     * Falls back to the whole path when the marker is absent, so an unfamiliar
     * URL shape is still cached - just less likely to hit after a re-sign.
     */
    static String canonicalKey(String url) {
        if (url == null || url.isEmpty()) return url;
        int q = url.indexOf('?');
        String path = q > 0 ? url.substring(0, q) : url;

        int marker = path.indexOf("/video/tos/");
        String stablePath = marker >= 0 ? path.substring(marker) : path;

        if (q <= 0) return stablePath;

        // Keep only params that name the media itself. The same path can carry
        // media-video-* and media-audio-* variants, which are entirely
        // different bytes and must not share an entry.
        StringBuilder extras = new StringBuilder();
        for (String param : url.substring(q + 1).split("&")) {
            if (param.startsWith("mime_type=") || param.startsWith("fid=")) {
                extras.append(extras.length() == 0 ? '?' : '&').append(param);
            }
        }
        return extras.length() == 0 ? stablePath : stablePath + extras;
    }
}
