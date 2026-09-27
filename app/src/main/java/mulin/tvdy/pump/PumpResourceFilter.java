package mulin.tvdy.pump;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.util.HashMap;

/**
 * Blocks subresources the pump WebView downloads but can never show.
 * <p>
 * The pump view is permanently {@code alpha=0} - it exists only so douyin.com's
 * own JS will issue signed feed XHRs for us to capture. Every image it paints
 * and every font it rasterizes is bandwidth and CPU spent against the two
 * things the user is actually waiting for on a cold start: the feed request
 * itself, and the first video segment ExoPlayer is trying to buffer.
 * <p>
 * Scope is deliberately narrow - images and fonts only:
 * <ul>
 *   <li>Media is <em>not</em> blocked here, though the page's {@code <video>}
 *       elements do allocate real {@code MediaCodec} decoders (logcat:
 *       {@code cr_MediaCodecBridge: create MediaCodec video decoder, mime
 *       video/hevc}, while every ExoPlayer selection in that session was
 *       H.264). Blocking the track URLs was tried and did nothing: the player
 *       gets its bytes through MediaSource over fetch, not a {@code src}
 *       request, so there is nothing here to intercept. Stopping it would mean
 *       defeating MediaSource in the page's own JS.</li>
 *   <li>Scripts, stylesheets and XHR are untouched, so the feed pipeline is
 *       unaffected.</li>
 *   <li>Cover art is unaffected either way: the player loads it natively via
 *       {@link mulin.tvdy.player.SimpleImageLoader}, never through this
 *       WebView.</li>
 * </ul>
 */
final class PumpResourceFilter {

    private static final String[] IMAGE_SUFFIXES = {
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".bmp", ".avif",
    };

    private static final String[] FONT_SUFFIXES = {
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
    };

    private PumpResourceFilter() {
    }

    /**
     * @return an empty 200 response to hand back in place of the real fetch,
     *         or {@code null} if this request should proceed normally.
     */
    @Nullable
    static WebResourceResponse blockIfNoise(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return null;
        // Only GETs are safe to shadow; a POST here would be a real API call.
        if (!"GET".equalsIgnoreCase(request.getMethod())) return null;

        // Never touch the document itself. Chrome's navigation Accept header
        // is "text/html,application/xhtml+xml,...,image/avif,image/webp,...
        // ,*/*" - it literally contains "image/", so the Accept test below
        // would otherwise match the main HTML request and hand the WebView an
        // empty document, which is a blank page with no JS running at all.
        if (request.isForMainFrame()) return null;

        String scheme = request.getUrl().getScheme();
        if (scheme == null) return null;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // data: / blob: / content: never reach the network anyway.
            return null;
        }

        String url = request.getUrl().toString().toLowerCase();
        String accept = header(request, "Accept");

        // Belt and braces alongside the isForMainFrame() check above: anything
        // that advertises HTML is a document, not art.
        boolean documentish = accept != null && accept.contains("text/html");

        if (!documentish && accept != null && accept.contains("image/")) return empty("image/png");
        if (!documentish && accept != null && accept.contains("font/")) return empty("font/woff2");

        // Douyin serves a lot of this art from URL paths without a file
        // extension, so the Accept header above is the primary signal and
        // these suffixes are only the fallback.
        if (hasSuffix(url, IMAGE_SUFFIXES)) return empty("image/png");
        if (hasSuffix(url, FONT_SUFFIXES)) return empty("font/woff2");

        return null;
    }

    @Nullable
    private static String header(WebResourceRequest request, String name) {
        try {
            String value = request.getRequestHeaders().get(name);
            return value != null ? value.toLowerCase() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasSuffix(String url, String[] suffixes) {
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * An empty 200 rather than a 404: the page then sees a successful load of
     * a zero-byte resource, which keeps it on its normal path instead of
     * routing into an error/retry branch we would have to reason about.
     * <p>
     * Built fresh per call - {@code shouldInterceptRequest} runs on several
     * WebView IO threads at once, and the returned body stream carries a read
     * position, so a shared instance would hand a half-consumed stream to the
     * next request.
     */
    private static WebResourceResponse empty(String mimeType) {
        return new WebResourceResponse(
                mimeType,
                "utf-8",
                200,
                "OK",
                new HashMap<String, String>(),
                new ByteArrayInputStream(new byte[0]));
    }
}
