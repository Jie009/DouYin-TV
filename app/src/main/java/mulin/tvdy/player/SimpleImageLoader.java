package mulin.tvdy.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mulin.tvdy.DouyinConstants;

/**
 * Cover-thumbnail fetcher with an in-memory cache and background preloading.
 * Shows a still frame (plus a blurred backdrop for pillarboxing) while
 * ExoPlayer buffers the real video.
 */
final class SimpleImageLoader {

    private static final int CACHE_MAX_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<String> PRELOADING = new HashSet<>();

    private static final LruCache<String, Bitmap> SHARP_CACHE = new LruCache<>(CACHE_MAX_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final LruCache<String, Bitmap> BACKDROP_CACHE = new LruCache<>(CACHE_MAX_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private SimpleImageLoader() {
    }

    static boolean isCached(String url) {
        return url != null && !url.isEmpty() && SHARP_CACHE.get(url) != null;
    }

    /**
     * Returns {@code true} when a cached bitmap was applied synchronously.
     * <p>
     * Falls back to the disk cache, and does so synchronously: this runs while
     * the user is staring at a black screen waiting for the first frame, and a
     * cover is a few tens of KB. Letting it resolve on the background executor
     * instead would mean the snapshot path could not populate the first screen
     * before the video decoder starts - which is the case this exists for.
     */
    static boolean applyCachedCover(String url, ImageView sharpTarget, ImageView backdropTarget) {
        if (url == null || url.isEmpty()) return false;
        Bitmap sharp = SHARP_CACHE.get(url);
        if (sharp == null) {
            byte[] onDisk = CoverDiskCache.read(url);
            if (onDisk == null) return false;
            sharp = BitmapFactory.decodeByteArray(onDisk, 0, onDisk.length);
            if (sharp == null) return false;
            cacheCover(url, sharp);
        }
        sharpTarget.setTag(url);
        sharpTarget.setImageBitmap(sharp);
        backdropTarget.setTag(url);
        Bitmap backdrop = BACKDROP_CACHE.get(url);
        backdropTarget.setImageBitmap(backdrop);
        return true;
    }

    /** Fetches and caches a cover in the background so it is ready before transition. */
    static void preload(String url) {
        if (url == null || url.isEmpty() || isCached(url)) return;
        synchronized (PRELOADING) {
            if (!PRELOADING.add(url)) return;
        }
        EXECUTOR.execute(() -> {
            try {
                cacheCover(url, fetch(url));
            } finally {
                synchronized (PRELOADING) {
                    PRELOADING.remove(url);
                }
            }
        });
    }

    static void load(String url, ImageView target) {
        target.setTag(url);
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Bitmap cached = SHARP_CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = fetchAndCache(url);
            MAIN_HANDLER.post(() -> {
                if (url.equals(target.getTag())) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    static void loadWithBackdrop(String url, ImageView sharpTarget, ImageView backdropTarget) {
        loadWithBackdrop(url, sharpTarget, backdropTarget, null);
    }

    static void loadWithBackdrop(
            String url,
            ImageView sharpTarget,
            ImageView backdropTarget,
            Runnable onLoaded) {
        sharpTarget.setTag(url);
        backdropTarget.setTag(url);
        if (url == null || url.isEmpty()) {
            sharpTarget.setImageDrawable(null);
            backdropTarget.setImageDrawable(null);
            if (onLoaded != null) onLoaded.run();
            return;
        }
        if (applyCachedCover(url, sharpTarget, backdropTarget)) {
            if (onLoaded != null) onLoaded.run();
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap sharp = fetchAndCache(url);
            Bitmap backdrop = BACKDROP_CACHE.get(url);
            MAIN_HANDLER.post(() -> {
                if (url.equals(sharpTarget.getTag())) {
                    sharpTarget.setImageBitmap(sharp);
                }
                if (url.equals(backdropTarget.getTag())) {
                    backdropTarget.setImageBitmap(backdrop);
                }
                if (onLoaded != null && url.equals(sharpTarget.getTag())) {
                    onLoaded.run();
                }
            });
        });
    }

    private static Bitmap fetchAndCache(String url) {
        Bitmap bitmap = SHARP_CACHE.get(url);
        if (bitmap != null) return bitmap;
        // Disk before network: covers are signed and short-lived, so on a cold
        // start the URL is usually already stale, but the bytes are the same
        // image and were saved on a key that does not include the signature.
        byte[] onDisk = CoverDiskCache.read(url);
        if (onDisk != null) {
            Bitmap fromDisk = BitmapFactory.decodeByteArray(onDisk, 0, onDisk.length);
            if (fromDisk != null) return cacheCover(url, fromDisk);
        }
        Bitmap fetched = fetch(url);
        if (fetched != null) {
            CoverDiskCache.write(url, fetched);
        }
        return cacheCover(url, fetched);
    }

    private static Bitmap cacheCover(String url, Bitmap sharp) {
        if (sharp != null) {
            SHARP_CACHE.put(url, sharp);
            BACKDROP_CACHE.put(url, BlurUtils.makeBackdrop(sharp));
        }
        return sharp;
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", DouyinConstants.DESKTOP_USER_AGENT);
            connection.setRequestProperty("Referer", DouyinConstants.REFERER);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
