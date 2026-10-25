package mulin.tvdy.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists the last good feed page as a playlist so a cold start can start
 * playing without booting douyin.com's SPA.
 * <p>
 * This exists because the pump's WebView path is the whole cold start: measured
 * on the TV, 12.6s of SPA bytecode boot plus ~19s waiting for the page to issue
 * its own signed feed XHR, before the app has anything to play. Meanwhile
 * {@code PlayerActivity.onCreate} already calls {@code startInitialPlayback()},
 * which only needs videos to already be in {@link FeedRepository}. If the
 * snapshot is on disk, that call succeeds immediately and the WebView never
 * blocks the first frame.
 * <p>
 * What is stored is the <em>distilled</em> form of each {@link FeedVideo} (see
 * its {@code toSnapshotJson}), not the raw response body. The raw body is ~145KB
 * per item of almost entirely unread fields, and re-parsing it cost a measured
 * 4.2s of the 7.8s cold start - 2.5s in {@code org.json} plus 1.8s re-deriving
 * URLs. The distilled form is a few KB per item and rebuilds each video by
 * filling in a constructor, so the whole read is a few tens of milliseconds.
 * <p>
 * The trade-off is that the CDN URLs are stored already <em>resolved</em>, so
 * their signatures are as old as the file. That is acceptable because expired
 * links are not fatal: playback falls back to the existing candidate-retry path,
 * and {@link #MAX_AGE_MS} bounds how stale the file can be before it is ignored
 * entirely and the network path is used.
 */
public final class FeedSnapshotStore {

    private static final String TAG = "FeedSnapshot";

    private static final String FILE_NAME = "feed-snapshot.json";
    /** Written alongside the snapshot so a torn write is detectable on read. */
    private static final String TMP_SUFFIX = ".tmp";

    /**
     * Bumped whenever the stored shape changes. A file carrying any other value
     * is discarded rather than parsed, so an upgrade never tries to read the
     * previous format with the current one's rules - including the v1 raw-item
     * bodies, which {@link FeedVideo#fromSnapshotJson} is not written for.
     */
    private static final int FORMAT_VERSION = 2;

    /**
     * Snapshots older than this are ignored at startup and refreshed from the
     * network instead. Long enough that a TV switched off overnight still opens
     * fast in the morning, short enough that the feed is not a week stale.
     */
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;

    /**
     * Keeps the file small enough to read on a CPU-starved box: this is far
     * more than {@code PRELOAD_AHEAD} needs to fill the first screen.
     */
    private static final int MAX_ITEMS = 20;

    /** Hard cap on what {@link #read} will load; a larger file is corrupt. */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final AtomicBoolean WRITE_IN_FLIGHT = new AtomicBoolean(false);

    private FeedSnapshotStore() {
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /**
     * Reads the stored snapshot synchronously. Called once from
     * {@code PlayerActivity.onCreate} before the pump is started, so it must
     * stay cheap: the stored form is a few tens of KB and rebuilds each video
     * without any URL re-derivation.
     *
     * @return the stored videos, or {@code null} if there is none, it is stale,
     *         it is an older format, or it fails to parse.
     */
    public static List<FeedVideo> read(Context context) {
        File f = file(context);
        if (!f.isFile()) return null;

        long ageMs = System.currentTimeMillis() - f.lastModified();
        if (ageMs > MAX_AGE_MS || ageMs < 0) {
            Log.d(TAG, "snapshot stale (age=" + ageMs + "ms), ignoring");
            // Don't delete here: the pump may still be offline, and a stale
            // snapshot that still plays beats an empty screen. It is simply
            // not used to skip the network path.
            return null;
        }

        try {
            long t0 = System.nanoTime();
            byte[] bytes = readAll(f);
            long tRead = System.nanoTime();
            if (bytes.length == 0) return null;

            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.optInt("v", -1) != FORMAT_VERSION) {
                Log.d(TAG, "snapshot format v=" + root.optInt("v", -1) + ", discarding");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            List<FeedVideo> videos = deserialize(root.optJSONArray("items"));
            long tParse = System.nanoTime();
            Log.d(TAG, "snapshot loaded items=" + (videos == null ? 0 : videos.size())
                    + " age=" + ageMs + "ms"
                    + " bytes=" + bytes.length
                    + " fileMs=" + ((tRead - t0) / 1_000_000)
                    + " parseMs=" + ((tParse - tRead) / 1_000_000));
            return videos;
        } catch (Exception e) {
            Log.w(TAG, "snapshot unreadable, discarding", e);
            // A corrupt file would otherwise fail on every launch.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    /**
     * Stores the given videos for the next cold start.
     * <p>
     * Writes from a background thread and never from the caller's, because the
     * pump delivers this on the main thread while the user is waiting for the
     * first frame.
     */
    public static void write(Context context, List<FeedVideo> videos) {
        if (videos == null || videos.isEmpty()) return;

        final Context app = context.getApplicationContext();
        // Snapshot the list on the caller's thread: the repository's copy is
        // free to be mutated (drained by the player) before the worker runs.
        final List<FeedVideo> copy = new ArrayList<>(videos);
        // One write at a time; later captures win because they are newer, and a
        // dropped write only costs the next launch its fast path.
        if (!WRITE_IN_FLIGHT.compareAndSet(false, true)) return;

        new Thread(() -> {
            // Trimming and serialising happen here, not in the caller: this is
            // invoked from the pump's main-thread capture handler, on top of the
            // video the user is watching.
            String payload = serialize(copy);
            if (payload == null) {
                WRITE_IN_FLIGHT.set(false);
                return;
            }

            File tmp = new File(app.getFilesDir(), FILE_NAME + TMP_SUFFIX);
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                // Atomic replace: a reader either sees the old file or the new
                // one, never a half-written snapshot.
                File dest = file(app);
                if (!tmp.renameTo(dest)) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    if (!tmp.renameTo(dest)) {
                        Log.w(TAG, "snapshot rename failed");
                    }
                }
                Log.d(TAG, "snapshot saved items=" + copy.size()
                        + " bytes=" + payload.length());
            } catch (Exception e) {
                Log.w(TAG, "snapshot write failed", e);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } finally {
                WRITE_IN_FLIGHT.set(false);
            }
        }, "feed-snapshot-write").start();
    }

    /**
     * Serialises up to {@link #MAX_ITEMS} videos to the on-disk form.
     * <p>
     * Pure and {@code Context}-free so the format can be round-tripped in a
     * JVM unit test.
     *
     * @return the JSON text, or {@code null} if nothing survived the trim.
     */
    static String serialize(List<FeedVideo> videos) {
        try {
            JSONArray items = new JSONArray();
            for (int i = 0; i < videos.size() && i < MAX_ITEMS; i++) {
                FeedVideo video = videos.get(i);
                if (video != null) items.put(video.toSnapshotJson());
            }
            if (items.length() == 0) return null;

            JSONObject root = new JSONObject();
            root.put("v", FORMAT_VERSION);
            root.put("items", items);
            return root.toString();
        } catch (Exception e) {
            Log.w(TAG, "snapshot serialise failed", e);
            return null;
        }
    }

    /** Inverse of {@link #serialize}; {@code null} when nothing is playable. */
    static List<FeedVideo> deserialize(JSONArray items) {
        if (items == null) return null;
        List<FeedVideo> videos = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            FeedVideo video = FeedVideo.fromSnapshotJson(items.optJSONObject(i));
            if (video != null) videos.add(video);
        }
        return videos.isEmpty() ? null : videos;
    }

    private static byte[] readAll(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len <= 0 || len > MAX_FILE_BYTES) return new byte[0];
            byte[] buf = new byte[(int) len];
            raf.readFully(buf);
            return buf;
        }
    }
}