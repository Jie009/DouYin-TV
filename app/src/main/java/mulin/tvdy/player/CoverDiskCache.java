package mulin.tvdy.player;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import mulin.tvdy.TvdyApplication;

/**
 * Small on-disk cache of cover images, so the pre-playback screen is populated
 * on a cold start instead of showing black while the stored (and by then
 * usually expired) cover URL is re-fetched.
 * <p>
 * {@link SimpleImageLoader}'s in-memory LRU dies with the process, which is
 * exactly when the snapshot path needs a cover: the feed snapshot can supply
 * the video URL, but the frame the user sees before the video decodes is the
 * cover. Caching cover <em>bytes</em> rather than the URL matters for the same
 * reason the media cache keys on a stripped URL - douyin signs its CDN links,
 * so the same image arrives under a different query string every session.
 */
final class CoverDiskCache {

    private static final String TAG = "CoverDisk";

    private static final String DIR = "cover-cache";
    /** Bounded well below the media cache: covers are cosmetic, videos are not. */
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    /** Applied when trimming, to avoid trimming again on the very next write. */
    private static final long TRIM_TO_BYTES = 12L * 1024 * 1024;

    private CoverDiskCache() {
    }

    static byte[] read(String url) {
        File f = fileFor(url);
        if (f == null || !f.isFile()) return null;
        try {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long len = raf.length();
                if (len <= 0 || len > 4 * 1024 * 1024) return null;
                byte[] buf = new byte[(int) len];
                raf.readFully(buf);
                return buf;
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    static void write(String url, android.graphics.Bitmap bitmap) {
        File dir = dir();
        if (dir == null || bitmap == null) return;
        File f = fileFor(url);
        if (f == null) return;
        try {
            byte[] bytes = encode(bitmap);
            if (bytes == null) return;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
        } catch (Exception e) {
            Log.d(TAG, "cover write failed", e);
        }
        trim(dir);
    }

    private static byte[] encode(android.graphics.Bitmap bitmap) {
        // Re-encoding as JPEG keeps entries small; the loader only needs a
        // bitmap, and it already blurs a copy for the backdrop.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        return bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                ? out.toByteArray()
                : null;
    }

    private static File dir() {
        Application app = TvdyApplication.appContext();
        if (app == null) return null;
        File dir = new File(app.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    private static File fileFor(String url) {
        File dir = dir();
        if (dir == null || url == null || url.isEmpty()) return null;
        return new File(dir, key(url) + ".jpg");
    }

    /**
     * Key on the cover's stable path, dropping the query string: the signature
     * parameters there are re-issued per session while the image is the same.
     */
    private static String key(String url) {
        String stable = MediaCacheStore.canonicalKey(url);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(stable.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // A non-hash key would risk illegal filename characters.
            return Integer.toHexString(stable.hashCode());
        }
    }

    /** Evicts oldest-first until the directory is back under the soft cap. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= TRIM_TO_BYTES) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
        Log.d(TAG, "trimmed cover cache to " + total + " bytes");
    }
}
