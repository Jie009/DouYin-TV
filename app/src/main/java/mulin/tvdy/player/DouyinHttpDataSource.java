package mulin.tvdy.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mulin.tvdy.DouyinConstants;

/**
 * Refreshes Douyin session cookies and Referer on every open() — the pump
 * WebView writes cookies after ExoPlayer is constructed, so a one-shot header
 * map on the factory would stay empty and CDN returns 403.
 * <p>
 * Reads are served through {@link MediaCacheStore} when it is available. That
 * cache is what lets a cold start reach the first frame without paying a CDN
 * round trip: the feed snapshot supplies a signed URL, and the bytes for it are
 * usually already on disk from the previous session. Since douyin re-signs the
 * link on every feed fetch, the cache is keyed by
 * {@link MediaCacheStore#canonicalKey} rather than the raw URL - see there for
 * the URL shape this relies on.
 * <p>
 * This class implements {@link HttpDataSource} itself rather than delegating
 * to an {@code HttpDataSource} field, because the cache wrapper
 * ({@link CacheDataSource}) implements only {@link DataSource} — it is not an
 * {@code HttpDataSource}, so a cast to one would fail at runtime. The two
 * HTTP-specific accessors are handled per delegate type below, and the
 * request-property calls are forwarded so the upstream source in the cache
 * chain still receives them.
 * <p>
 * Caching is an optimisation and must never be the reason a play attempt fails,
 * so cache errors fall through to the upstream HTTP source
 * ({@link CacheDataSource#FLAG_IGNORE_CACHE_ON_ERROR}).
 */
@UnstableApi
final class DouyinHttpDataSource implements HttpDataSource {

    private final DataSource delegate;
    /** The raw HTTP source, used when a cached delegate cannot answer an HTTP-only call. */
    @Nullable
    private final HttpDataSource httpDelegate;

    /** Mirrors setRequestProperty for delegates that do not expose the HTTP API. */
    private final Map<String, String> requestProperties = new LinkedHashMap<>();

    DouyinHttpDataSource(DataSource delegate, @Nullable HttpDataSource httpDelegate) {
        this.delegate = delegate;
        this.httpDelegate = httpDelegate;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        String awemeId = DouyinPlaybackRegistry.findAwemeId(dataSpec.uri.toString());
        Map<String, String> headers = DouyinConstants.buildPlaybackRequestHeaders(awemeId);
        if (CACHE_DEBUG) {
            String raw = dataSpec.uri.toString();
            android.util.Log.i("MediaCacheDbg", "open key=" + MediaCacheStore.canonicalKey(raw)
                    + " raw=" + raw.substring(0, Math.min(160, raw.length())));
        }
        try {
            return delegate.open(dataSpec.withRequestHeaders(headers));
        } catch (HttpDataSourceException e) {
            throw e;
        } catch (IOException e) {
            throw wrap(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }
    }

    /** Always-on for now: this is the diagnostic for the colour regression. */
    private static final boolean CACHE_DEBUG = true;

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        try {
            return delegate.read(buffer, offset, length);
        } catch (HttpDataSourceException e) {
            throw e;
        } catch (IOException e) {
            throw wrap(e, currentSpec(), HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        try {
            delegate.close();
        } catch (HttpDataSourceException e) {
            throw e;
        } catch (IOException e) {
            throw wrap(e, currentSpec(), HttpDataSourceException.TYPE_CLOSE);
        }
    }

    @Override
    @Nullable
    public android.net.Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        // CacheDataSource defines getResponseHeaders() without implementing
        // HttpDataSource, so this cannot be a cast - check both shapes.
        if (delegate instanceof HttpDataSource) {
            return ((HttpDataSource) delegate).getResponseHeaders();
        }
        if (delegate instanceof CacheDataSource) {
            return ((CacheDataSource) delegate).getResponseHeaders();
        }
        return httpDelegate != null ? httpDelegate.getResponseHeaders() : Collections.emptyMap();
    }

    @Override
    public int getResponseCode() {
        if (delegate instanceof HttpDataSource) {
            return ((HttpDataSource) delegate).getResponseCode();
        }
        // CacheDataSource serves getResponseHeaders() but has no status code of
        // its own. The only caller is ExoPlayer's error reporting, and the read
        // itself succeeded, so report the code the last real HTTP response
        // produced rather than inventing one.
        return httpDelegate != null ? httpDelegate.getResponseCode() : 200;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public void setRequestProperty(String name, String value) {
        requestProperties.put(name, value);
        if (httpDelegate != null) {
            httpDelegate.setRequestProperty(name, value);
        }
    }

    @Override
    public void clearRequestProperty(String name) {
        requestProperties.remove(name);
        if (httpDelegate != null) {
            httpDelegate.clearRequestProperty(name);
        }
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
        if (httpDelegate != null) {
            httpDelegate.clearAllRequestProperties();
        }
    }

    /** Best-effort DataSpec for wrapping an IOException; never null. */
    private DataSpec currentSpec() {
        android.net.Uri uri = delegate.getUri();
        return new DataSpec(uri != null ? uri : android.net.Uri.EMPTY);
    }

    private static HttpDataSourceException wrap(IOException cause, DataSpec dataSpec, int type) {
        // Preserve an already-typed cause so ExoPlayer reads the real reason
        // (e.g. an HTTP 403) instead of a generic wrapper.
        if (cause instanceof HttpDataSourceException) {
            return (HttpDataSourceException) cause;
        }
        return HttpDataSourceException.createForIOException(cause, dataSpec, type);
    }

    @UnstableApi
    static final class Factory implements DataSource.Factory {

        private final Context context;
        private final DefaultHttpDataSource.Factory delegateFactory;

        Factory(Context context) {
            this.context = context.getApplicationContext();
            delegateFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(DouyinConstants.DESKTOP_USER_AGENT)
                    .setAllowCrossProtocolRedirects(true);
        }

        @Override
        public DouyinHttpDataSource createDataSource() {
            try {
                SimpleCache cache = MediaCacheStore.get(context);
                if (cache != null) {
                    CacheDataSource cached = new CacheDataSource.Factory()
                            .setCache(cache)
                            .setUpstreamDataSourceFactory(delegateFactory)
                            // Do not block on the cache (a miss starts the
                            // network read immediately) and ignore cache errors
                            // so a bad entry degrades to a plain HTTP fetch
                            // rather than a playback failure.
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            .setCacheKeyFactory(uri -> MediaCacheStore.canonicalKey(uri.toString()))
                            .createDataSource();
                    return new DouyinHttpDataSource(cached, null);
                }
            } catch (Exception ignored) {
                // Cache is an optimisation; fall back to plain HTTP.
            }
            DefaultHttpDataSource http = delegateFactory.createDataSource();
            return new DouyinHttpDataSource(http, http);
        }
    }
}
