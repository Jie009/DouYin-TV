package mulin.tvdy.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * The media cache only pays off if the same video resolves to the same key
 * across sessions. Douyin re-signs its CDN links on every feed fetch, so these
 * tests pin the property that actually matters: two URLs for the same media
 * file, differing only in signing material, must collapse to one key - while
 * genuinely different media must not.
 * <p>
 * The URL shapes below are taken from a real captured feed.
 */
public class MediaCacheStoreKeyTest {

    private static final String PATH =
            "/4281c66192517c9342268c58626c8324/6ad77f21/video/tos/cn/tos-cn-vd-0026"
                    + "/8be43d39793b4eb5ba644bae1d70e5e3/media-video-avc1/";

    private static final String SESSION_A =
            "https://v5-dy-ov-experiment.zjcdn.com" + PATH
                    + "?a=6383&ch=0&br=414&bt=414&mime_type=video_mp4&qs=12"
                    + "&dy_q=1792419190&l=20261019221309671E67E17A4A22612382";

    /** Same file, re-signed: different path prefix, query and timestamp. */
    private static final String SESSION_B =
            "https://v5-dy-ov-experiment.zjcdn.com"
                    + "/1078aec967151d0013ec88a5269fb72c/6ad657d1/video/tos/cn/tos-cn-vd-0026"
                    + "/8be43d39793b4eb5ba644bae1d70e5e3/media-video-avc1/"
                    + "?a=6383&ch=0&br=986&bt=986&mime_type=video_mp4&qs=11"
                    + "&dy_q=1792999999&l=20261025000000000ABCDEF";

    /** A different media file on the same host and bucket. */
    private static final String OTHER_MEDIA =
            "https://v5-dy-ov-experiment.zjcdn.com"
                    + "/4281c66192517c9342268c58626c8324/6ad77f21/video/tos/cn/tos-cn-vd-0026"
                    + "/deadbeefdeadbeefdeadbeefdeadbeef/media-video-avc1/?dy_q=1792419190";

    /** The audio half of a split stream for the same aweme. */
    private static final String AUDIO_TRACK =
            "https://v5-dy-ov-experiment.zjcdn.com"
                    + "/4281c66192517c9342268c58626c8324/6ad77f21/video/tos/cn/tos-cn-vd-0026"
                    + "/8be43d39793b4eb5ba644bae1d70e5e3/media-audio-und-mp4a/?mime_type=video_mp4";

    @Test
    public void reSignedUrlForSameMediaHitsTheSameKey() {
        assertEquals(
                MediaCacheStore.canonicalKey(SESSION_A),
                MediaCacheStore.canonicalKey(SESSION_B));
    }

    @Test
    public void differentMediaDoesNotCollide() {
        assertNotEquals(
                MediaCacheStore.canonicalKey(SESSION_A),
                MediaCacheStore.canonicalKey(OTHER_MEDIA));
    }

    /**
     * Video and audio halves of a split stream share a path up to the final
     * filename segment, so a key that ignored that segment would serve audio
     * bytes as video.
     */
    @Test
    public void splitVideoAndAudioTracksStayDistinct() {
        assertNotEquals(
                MediaCacheStore.canonicalKey(SESSION_A),
                MediaCacheStore.canonicalKey(AUDIO_TRACK));
    }

    @Test
    public void keyDropsSigningMaterial() {
        String key = MediaCacheStore.canonicalKey(SESSION_A);
        // The two per-issue path segments are gone...
        assertEquals(false, key.contains("4281c66192517c9342268c58626c8324"));
        assertEquals(false, key.contains("6ad77f21"));
        // ...as is the whole query, which is pure signing material here.
        assertEquals(false, key.contains("dy_q"));
        assertEquals(false, key.contains("l=2026"));
        // ...while the content-addressed part survives.
        assertEquals(true, key.contains("8be43d39793b4eb5ba644bae1d70e5e3"));
        assertEquals(true, key.contains("media-video-avc1"));
    }

    /** A URL with no query string must still round-trip to something usable. */
    @Test
    public void urlWithoutQueryStillKeys() {
        String bare = "https://v5-dy-ov-experiment.zjcdn.com" + PATH;
        assertEquals(MediaCacheStore.canonicalKey(bare), MediaCacheStore.canonicalKey(bare));
        assertEquals(true, MediaCacheStore.canonicalKey(bare).contains("media-video-avc1"));
    }

    /** Unfamiliar hosts lose only the query, never the path. */
    @Test
    public void unknownHostFallsBackToFullPath() {
        String odd = "https://example.invalid/some/other/route.mp4?token=abc";
        String key = MediaCacheStore.canonicalKey(odd);
        assertEquals(true, key.contains("/some/other/route.mp4"));
        assertEquals(false, key.contains("token=abc"));
    }
}
