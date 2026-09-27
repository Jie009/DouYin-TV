package mulin.tvdy;

import android.app.Application;

public class TvdyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        StartupTrace.start();
        StartupTrace.mark("Application.onCreate enter");
        WebViewPumpWarmup.warm(this);
        StartupTrace.mark("Application.onCreate exit");
    }
}
