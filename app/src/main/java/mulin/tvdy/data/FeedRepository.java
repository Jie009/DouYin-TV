package mulin.tvdy.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import mulin.tvdy.StartupTrace;
import mulin.tvdy.TvdyApplication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide singleton buffer of playable videos, fed by the hidden data-pump
 * WebView and drained by the player UI.
 * <p>
 * Parsing a page of raw feed JSON is expensive: the payloads are multi-megabyte
 * and {@code org.json} costs ~2.5s per 2.8MB before {@link FeedVideo} even runs
 * (measured on the TV). Doing that on the main thread froze the UI for seconds
 * at a time on every pagination batch — the "video stutters" report — so
 * {@link #addAwemeList} keeps its shape for callers but does the parse on a
 * worker thread and hands the finished videos back to the main thread, which is
 * the only place {@link #queue} and its listeners are touched.
 * <p>
 * Because callers can no longer assume the buffer is populated by the time
 * {@code addAwemeList} returns, the resulting state change is announced with
 * {@link Listener#onBufferChanged(int)} (already wired to start playback) rather
 * than being observed synchronously. Callers needing the rows immediately should
 * use {@link #addVideosBlocking}.
 */
public final class FeedRepository {

    private static final String TAG = "FeedRepository";

    // Once the buffer drops below this many un-watched videos, ask the pump
    // for another page rather than waiting for it to run dry.
    private static final int LOW_WATER_MARK = 5;

    // Caps memory growth of the dedup set over a long-running session; oldest
    // ids are evicted first since they're the least likely to reappear.
    private static final int MAX_SEEN_IDS = 2000;
    /** Stop auto-paging when this many consecutive batches are all filtered out. */
    private static final int MAX_ALL_FILTERED_BATCHES = 10;
    private static final long PAGE_REQUEST_COOLDOWN_MS = 2_000;

    private static FeedRepository instance;

    /**
     * Single worker for feed parsing. One thread, not a pool: batches must be
     * applied in the order they arrived or the playlist order stops matching
     * the feed's.
     */
    private final ExecutorService parser = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feed-parse");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized FeedRepository getInstance() {
        if (instance == null) {
            instance = new FeedRepository();
        }
        return instance;
    }

    /** Notified whenever the buffer size changes (new data in, or one consumed). */
    public interface Listener {
        void onBufferChanged(int size);
    }

    private final ArrayDeque<FeedVideo> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> seenAwemeIds = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private PageRequester pageRequester;
    private boolean pageRequestInFlight = false;
    private long lastPageRequestAt = 0;
    private int consecutiveAllFilteredBatches = 0;
    private boolean watchedFilterBypass = false;

    private final WatchedAwemeStore watchedStore = WatchedAwemeStore.getInstance();

    private FeedRepository() {
    }

    public void setPageRequester(PageRequester requester) {
        this.pageRequester = requester;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Parses a raw {@code aweme_list} JSON array, drops items with no
     * playable url (ads, live cards, ...) and items already seen, and
     * enqueues the rest.
     */
    /**
     * Parses {@code awemeList} off the main thread and enqueues the results.
     * <p>
     * Returns as soon as the work is queued, so the caller (the pump's main-
     * thread response handler) never blocks on the parse. The buffer and the
     * listener callbacks stay main-thread-only.
     */
    public void addAwemeList(JSONArray awemeList) {
        if (awemeList == null) {
            pageRequestInFlight = false;
            maybeRequestMore();
            return;
        }
        parser.execute(() -> {
            ParsedBatch parsed = parseAwemeList(awemeList);
            // A live page, so it is worth persisting for the next cold start.
            // Handing the parse over to the snapshot store here means the disk
            // form is written from data already in memory, with no second parse.
            mainHandler.post(() -> commitParsed(parsed, /*persistSnapshot=*/ true));
        });
    }

    /**
     * Enqueues already-parsed videos on the calling thread, before returning.
     * <p>
     * For the startup snapshot only: there the videos come off disk already
     * distilled, and the caller is on the main thread holding up the first
     * frame, so hopping to a worker and back would add a round trip for
     * nothing. Live pump batches must use {@link #addAwemeList} instead, or
     * playback stutters.
     * <p>
     * The watched filter is re-applied here rather than trusted to the file:
     * history can sync between the snapshot being written and the next launch,
     * and an item watched since then must not be played just because it is on
     * disk.
     */
    public void addVideosBlocking(List<FeedVideo> videos) {
        if (videos == null || videos.isEmpty()) return;
        List<FeedVideo> fresh = new ArrayList<>(videos.size());
        boolean anyWatched = false;
        for (FeedVideo video : videos) {
            if (video == null) continue;
            if (!watchedFilterBypass && watchedStore.isWatched(video.awemeId)) {
                anyWatched = true;
                continue;
            }
            fresh.add(video);
        }
        // Never re-persist what was just read: that would refresh the file's
        // timestamp on every launch, and its MAX_AGE_MS would never expire.
        commitParsed(new ParsedBatch(fresh, videos.size(), anyWatched),
                /*persistSnapshot=*/ false);
    }

    /** A parsed page plus the counts {@link #commitParsed} needs to page onward. */
    private static final class ParsedBatch {
        final List<FeedVideo> videos;
        final int rawCount;
        final boolean anyWatched;

        ParsedBatch(List<FeedVideo> videos, int rawCount, boolean anyWatched) {
            this.videos = videos;
            this.rawCount = rawCount;
            this.anyWatched = anyWatched;
        }
    }

    /**
     * Pure, thread-safe half of {@link #addAwemeList}: turns raw items into
     * playable videos. Touches no shared state beyond the watched store, which
     * is itself a concurrent-safe set.
     */
    private ParsedBatch parseAwemeList(JSONArray awemeList) {
        List<FeedVideo> out = new ArrayList<>(awemeList.length());
        // Dedup within the batch on a local set: the shared seen-set is only
        // consulted on the main thread in commitParsed().
        HashSet<String> batchIds = new HashSet<>();
        boolean anyWatched = false;
        for (int i = 0; i < awemeList.length(); i++) {
            JSONObject item = awemeList.optJSONObject(i);
            if (item == null) continue;

            String awemeId = item.optString("aweme_id", "");
            if (!watchedFilterBypass
                    && (FeedVideo.isMarkedWatchedByServer(item)
                    || watchedStore.isWatched(awemeId))) {
                anyWatched = true;
                continue;
            }

            FeedVideo video = FeedVideo.fromAwemeItem(item);
            if (video == null) continue;
            if (!batchIds.add(video.awemeId)) continue;
            out.add(video);
        }
        return new ParsedBatch(out, awemeList.length(), anyWatched);
    }

    /** Main-thread half: applies a parsed batch to the queue and notifies. */
    private void commitParsed(ParsedBatch batch, boolean persistSnapshot) {
        int added = 0;
        for (FeedVideo video : batch.videos) {
            if (!seenAwemeIds.add(video.awemeId)) continue;
            trimSeenIdsIfNeeded();
            queue.addLast(video);
            added++;
        }

        Log.d(TAG, "commitParsed: +" + added + " raw=" + batch.rawCount
                + " bufferSize=" + queue.size());

        // Persist what entered the queue - not the raw page, which is mostly
        // unread fields. See FeedSnapshotStore for why the distilled form is
        // what a cold start wants.
        if (persistSnapshot && added > 0) {
            persistSnapshotForColdStart();
        }

        if (added > 0 && queue.size() == added) {
            StartupTrace.mark("repository: first items queued (" + added + ")");
        }

        if (added == 0 && batch.rawCount > 0 && batch.anyWatched) {
            consecutiveAllFilteredBatches++;
            pageRequestInFlight = false;
            if (consecutiveAllFilteredBatches < MAX_ALL_FILTERED_BATCHES) {
                maybeRequestMore();
                return;
            }
            watchedFilterBypass = true;
            consecutiveAllFilteredBatches = 0;
            maybeRequestMore();
            return;
        } else if (added > 0) {
            consecutiveAllFilteredBatches = 0;
        }

        pageRequestInFlight = false;
        if (added > 0) {
            notifyBufferChanged();
        }
        maybeRequestMore();
    }

    /** Removes and returns the next video to play, or {@code null} if the buffer is empty. */
    public FeedVideo pollNext() {
        boolean skippedWatched = false;
        while (true) {
            FeedVideo next = queue.pollFirst();
            if (next == null) {
                if (skippedWatched) notifyBufferChanged();
                maybeRequestMore();
                return null;
            }
            // Defense in depth: history may arrive after items were enqueued.
            if (!watchedFilterBypass && watchedStore.isWatched(next.awemeId)) {
                Log.d(TAG, "pollNext skip watched " + next.awemeId);
                skippedWatched = true;
                continue;
            }
            notifyBufferChanged();
            maybeRequestMore();
            return next;
        }
    }

    /**
     * Drops buffered items that are now known-watched (e.g. after history sync).
     * @return number of items removed
     */
    public int purgeWatchedFromQueue() {
        if (watchedFilterBypass) return 0;
        int removed = 0;
        Iterator<FeedVideo> it = queue.iterator();
        while (it.hasNext()) {
            FeedVideo video = it.next();
            if (watchedStore.isWatched(video.awemeId)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            Log.d(TAG, "purgeWatchedFromQueue: -" + removed + " bufferSize=" + queue.size());
            notifyBufferChanged();
            maybeRequestMore();
        }
        return removed;
    }

    public int bufferSize() {
        return queue.size();
    }

    /**
     * Drops all buffered videos and the session dedup set. Call when the
     * Douyin session identity changes (login/logout) so stale pre-auth feed
     * items are not played after the pump reloads with new cookies.
     */
    public void reset() {
        queue.clear();
        seenAwemeIds.clear();
        pageRequestInFlight = false;
        consecutiveAllFilteredBatches = 0;
        watchedFilterBypass = false;
        watchedStore.bindSession();
        notifyBufferChanged();
    }

    /** Called when the user finishes watching a video in ExoPlayer. */
    public void markConsumed(String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return;
        watchedStore.markWatched(awemeId);
        seenAwemeIds.add(awemeId);
        trimSeenIdsIfNeeded();
    }

    /** Ask the pump for another page when the buffer is below {@link #LOW_WATER_MARK}. */
    public void kickstartPaging() {
        maybeRequestMore();
    }

    /**
     * Clears the in-flight page flag after a proactive fetch fails or times out
     * so {@link #maybeRequestMore()} can schedule another attempt later.
     */
    public void releasePageRequest() {
        pageRequestInFlight = false;
    }

    private void maybeRequestMore() {
        if (pageRequester == null || pageRequestInFlight) return;
        if (queue.size() >= LOW_WATER_MARK) return;
        long now = System.currentTimeMillis();
        if (now - lastPageRequestAt < PAGE_REQUEST_COOLDOWN_MS) return;
        pageRequestInFlight = true;
        lastPageRequestAt = now;
        pageRequester.requestNextPage();
    }

    private void trimSeenIdsIfNeeded() {
        while (seenAwemeIds.size() > MAX_SEEN_IDS) {
            Iterator<String> it = seenAwemeIds.iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * Hands the pending playlist to {@link FeedSnapshotStore} for the next
     * cold start.
     * <p>
     * Called only for batches that came off the network, and only while the
     * app is on the for-you feed, so the file always reflects a fresh capture
     * rather than a replay of itself. A missing application context (plain JVM
     * tests) and a disabled/absent store are both non-events - the next launch
     * simply falls back to the pump.
     */
    private void persistSnapshotForColdStart() {
        if (pageRequester == null) return; // No pump wired up: nothing captured.
        android.app.Application app = TvdyApplication.appContext();
        if (app == null) return;
        try {
            FeedSnapshotStore.write(app, new ArrayList<>(queue));
        } catch (Throwable t) {
            Log.w(TAG, "snapshot persist failed", t);
        }
    }

    private void notifyBufferChanged() {
        int size = queue.size();
        for (Listener listener : listeners) {
            listener.onBufferChanged(size);
        }
    }
}
