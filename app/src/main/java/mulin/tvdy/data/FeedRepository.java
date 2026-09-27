package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import mulin.tvdy.StartupTrace;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-wide singleton buffer of playable videos, fed by the hidden data-pump
 * WebView and drained by the player UI.
 * <p>
 * Not thread-safe by design: every caller in this app (the pump's
 * JavascriptInterface callbacks and the player Activity) runs on the main
 * thread, so no internal locking is done. If a future caller needs to touch
 * this from a background thread, add synchronization then.
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
    public void addAwemeList(JSONArray awemeList) {
        if (awemeList == null) {
            pageRequestInFlight = false;
            maybeRequestMore();
            return;
        }

        int added = 0;
        int skippedNoUrl = 0;
        int skippedDup = 0;
        int skippedWatched = 0;
        for (int i = 0; i < awemeList.length(); i++) {
            JSONObject item = awemeList.optJSONObject(i);
            if (item == null) continue;

            String awemeId = item.optString("aweme_id", "");
            if (!watchedFilterBypass
                    && (FeedVideo.isMarkedWatchedByServer(item)
                    || watchedStore.isWatched(awemeId))) {
                skippedWatched++;
                if (!awemeId.isEmpty()) watchedStore.markWatched(awemeId);
                continue;
            }

            FeedVideo video = FeedVideo.fromAwemeItem(item);
            if (video == null) {
                skippedNoUrl++;
                continue;
            }
            if (!seenAwemeIds.add(video.awemeId)) {
                skippedDup++;
                continue;
            }
            trimSeenIdsIfNeeded();
            queue.addLast(video);
            added++;
        }

        Log.d(TAG, "addAwemeList: +" + added + " noUrl=" + skippedNoUrl
                + " dup=" + skippedDup + " watched=" + skippedWatched
                + " bufferSize=" + queue.size());

        if (added > 0 && queue.size() == added) {
            StartupTrace.mark("repository: first items queued (" + added + ")");
        }

        if (added == 0 && awemeList.length() > 0 && skippedWatched > 0) {
            consecutiveAllFilteredBatches++;
            pageRequestInFlight = false;
            if (consecutiveAllFilteredBatches < MAX_ALL_FILTERED_BATCHES) {
                Log.d(TAG, "batch all watched/filtered, requesting next page ("
                        + consecutiveAllFilteredBatches + "/" + MAX_ALL_FILTERED_BATCHES + ")");
                maybeRequestMore();
                return;
            }
            Log.w(TAG, "too many all-watched batches, allowing repeats so playback can start");
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

    private void notifyBufferChanged() {
        int size = queue.size();
        for (Listener listener : listeners) {
            listener.onBufferChanged(size);
        }
    }
}
