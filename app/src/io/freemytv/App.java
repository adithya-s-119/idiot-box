package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process, and on Fire TV turns on the Live TV button's handler. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        FireTv.setUp(this);
    }
}
