package mulin.tvdy.data;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the snapshot format. A cold start now rebuilds {@link FeedVideo}s
 * straight from the stored JSON instead of re-deriving them from the raw aweme
 * item (see {@link FeedSnapshotStore}), so anything this round trip drops is
 * something the first screen silently loses - a missing cover, a lost fallback
 * URL - with no error to notice.
 */
public class FeedSnapshotStoreTest {

    @Test
    public void roundTripPreservesEveryPlaybackField() throws Exception {
        FeedVideo original = video("7123456789", "hello world",
                "https://cdn.example/cover.jpg", "https://cdn.example/play.mp4",
                new String[]{"https://cdn.example/play.mp4", "https://cdn.example/alt.mp4"},
                true, /*withSplit=*/false);

        List<FeedVideo> back = FeedSnapshotStore.deserialize(
                itemsOf(FeedSnapshotStore.serialize(java.util.Collections.singletonList(original))));

        assertNotNull(back);
        assertEquals(1, back.size());
        FeedVideo v = back.get(0);
        assertEquals(original.awemeId, v.awemeId);
        assertEquals(original.desc, v.desc);
        assertEquals(original.playUrl, v.playUrl);
        assertEquals(original.coverUrl, v.coverUrl);
        assertEquals(original.authorName, v.authorName);
        assertEquals(original.authorAvatarUrl, v.authorAvatarUrl);
        assertEquals(original.authorSecUid, v.authorSecUid);
        assertEquals(original.authorUniqueId, v.authorUniqueId);
        assertEquals(original.diggCount, v.diggCount);
        assertEquals(original.commentCount, v.commentCount);
        assertEquals(original.collectCount, v.collectCount);
        assertEquals(original.shareCount, v.shareCount);
        assertEquals(original.userDigged, v.userDigged);
        assertEquals(original.playUrlCandidates, v.playUrlCandidates);
    }

    @Test
    public void roundTripPreservesSplitStream() throws Exception {
        FeedVideo original = video("99", "d", null, "https://cdn.example/muxed.mp4",
                new String[]{"https://cdn.example/muxed.mp4"},
                true, /*withSplit=*/true);

        List<FeedVideo> back = FeedSnapshotStore.deserialize(
                itemsOf(FeedSnapshotStore.serialize(java.util.Collections.singletonList(original))));

        assertNotNull(back);
        FeedVideo v = back.get(0);
        assertTrue(v.hasSplitStream());
        assertEquals(original.splitCandidates.size(), v.splitCandidates.size());
        assertEquals(original.splitCandidates.get(0).videoUrl,
                v.splitCandidates.get(0).videoUrl);
        assertEquals(original.splitCandidates.get(0).audioUrl,
                v.splitCandidates.get(0).audioUrl);
        assertEquals(original.splitCandidates.get(0).videoBrKbps,
                v.splitCandidates.get(0).videoBrKbps);
        assertEquals(original.splitCandidates.get(0).hevc,
                v.splitCandidates.get(0).hevc);
    }

    /**
     * The whole point of the slim format is size: a real entry carries
     * 200-400 char signed URLs, so storing every candidate would put the file
     * back where it started.
     */
    @Test
    public void trimsCandidateLists() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add("https://cdn.example/muxed-" + i + ".mp4?br=" + i);
        FeedVideo original = video("1", "d", null, many.get(0),
                many.toArray(new String[0]), false, false);

        List<FeedVideo> back = FeedSnapshotStore.deserialize(
                itemsOf(FeedSnapshotStore.serialize(java.util.Collections.singletonList(original))));

        assertNotNull(back);
        assertTrue("candidates should be capped", back.get(0).playUrlCandidates.size() <= 8);
        // The head of the list - including playUrl - must survive the trim.
        assertEquals(many.get(0), back.get(0).playUrl);
        assertEquals(many.get(0), back.get(0).playUrlCandidates.get(0));
    }

    /** An entry with no playable URL is dropped, matching fromAwemeItem. */
    @Test
    public void dropsEntriesWithNoPlayableUrl() throws Exception {
        JSONArray items = new JSONArray();
        items.put(new JSONObject());                                    // no id
        JSONObject idOnly = new JSONObject();
        idOnly.put("i", "5");                                          // id, no url
        items.put(idOnly);
        assertNull(FeedSnapshotStore.deserialize(items));
    }

    @Test
    public void serializeRejectsEmptyInput() {
        assertNull(FeedSnapshotStore.serialize(new ArrayList<>()));
    }

    @Test
    public void serializeCapsItemCount() throws Exception {
        List<FeedVideo> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(video(String.valueOf(i), "d", null,
                    "https://cdn.example/p" + i + ".mp4", null, false, false));
        }
        List<FeedVideo> back = FeedSnapshotStore.deserialize(itemsOf(FeedSnapshotStore.serialize(many)));
        assertNotNull(back);
        assertEquals(20, back.size());
    }

    private static JSONArray itemsOf(String payload) throws Exception {
        assertNotNull(payload);
        JSONObject root = new JSONObject(payload);
        assertEquals(2, root.optInt("v", -1));
        JSONArray items = root.optJSONArray("items");
        assertNotNull(items);
        assertFalse(items.length() == 0);
        return items;
    }

    private static FeedVideo video(String id, String desc, String cover, String playUrl,
                                   String[] muxed, boolean userDigged, boolean withSplit) {
        List<String> candidates = new ArrayList<>();
        if (muxed != null) {
            for (String u : muxed) candidates.add(u);
        }
        List<FeedVideo.SplitPlayUrl> splits = new ArrayList<>();
        if (withSplit) {
            splits.add(new FeedVideo.SplitPlayUrl(
                    "https://cdn.example/sv.mp4", "https://cdn.example/sa.mp4", 424, true));
        }
        return FeedVideo.forSnapshotTest(id, desc, cover, playUrl, candidates, splits,
                "alice", "https://cdn.example/av.jpg", "SEC_uid", "alice_id",
                1234L, 56L, 7L, 8L, userDigged);
    }
}