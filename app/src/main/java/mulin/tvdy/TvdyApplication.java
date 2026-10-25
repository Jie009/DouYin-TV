package mulin.tvdy;

import android.app.Application;

public class TvdyApplication extends Application {

    private static Application instance;

    /**
     * Held for helpers that need a {@code Context} but are called from static
     * code paths with no direct handle on one (the cover cache, for example).
     * Set in {@link #onCreate}, so it is never null once the app is running.
     */
    public static Application appContext() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        StartupTrace.start();
        StartupTrace.mark("Application.onCreate enter");
        WebViewPumpWarmup.warm(this);
        warmMediaCache();
        StartupTrace.mark("Application.onCreate exit");
    }

    /**
     * Builds the media cache off the main thread ahead of the first playback.
     * <p>
     * {@code SimpleCache} opens a SQLite database on construction. Left lazy,
     * that cost lands inside the first {@code dataSource.createDataSource()}
     * call - i.e. on the critical path to the first frame, competing with the
     * WebView boot for the same cores. Here it only competes with app start.
     */
    private void warmMediaCache() {
        new Thread(() -> {
            try {
                mulin.tvdy.player.MediaCacheStore.get(this);
            } catch (Throwable ignored) {
                // Playback falls back to plain HTTP when the cache is absent.
            }
        }, "media-cache-warm").start();
    }
}
