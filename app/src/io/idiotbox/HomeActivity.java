package io.idiotbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Idiot Box home screen (Android's "Home" role in Idiot Box mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    // The music screensaver: after MusicSaverActivity.IDLE_MS without a key on the home screen while music plays.
    private final Handler idle = new Handler(Looper.getMainLooper());
    private long lastKey;
    private boolean idleArmed;
    private final Runnable checkIdle = new Runnable() {
        @Override public void run() {
            idleArmed = false;
            if (!Music.playing()) return;   // armed again when it plays (watchMusic)
            long quiet = SystemClock.uptimeMillis() - lastKey;
            if (hasWindowFocus() && quiet >= MusicSaverActivity.IDLE_MS) {
                startActivity(new Intent(HomeActivity.this, MusicSaverActivity.class));
            } else {
                armIdle(Math.max(1000, MusicSaverActivity.IDLE_MS - quiet));
            }
        }
    };
    private final Music.Listener watchMusic = () -> {
        if (Music.playing() && !idleArmed) armIdle(1000);
    };

    private void armIdle(long delay) {
        idle.removeCallbacks(checkIdle);
        idle.postDelayed(checkIdle, delay);
        idleArmed = true;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Idiot Box's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        askForTvListings();
        lastKey = SystemClock.uptimeMillis();
        if (home != null) {
            Music.addListener(watchMusic);
            armIdle(1000);
        }
    }

    /** Asked once per start: the picks come from the apps' Watch Next rows (TvPicks); a TMDB key only adds their art. */
    private static boolean askedTv;

    private void askForTvListings() {
        if (askedTv || home == null || TvPicks.allowed(this)) return;
        askedTv = true;
        requestPermissions(TvPicks.PERMISSIONS, 1);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (TvPicks.allowed(this)) recreate();
    }

    /**
     * Back from the music screensaver (or anything over the home screen): the idle time starts again. The screensaver
     * took the keys that closed it, so without this it would open again at once.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) lastKey = SystemClock.uptimeMillis();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        lastKey = SystemClock.uptimeMillis();
    }

    @Override protected void onPause() {
        Music.removeListener(watchMusic);
        idle.removeCallbacks(checkIdle);
        idleArmed = false;
        if (home != null) home.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (home != null) Recs.get(this).trimCovered();
        super.onStop();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Shown but the TV is low (a music app's hidden browser can take a third of it): the kept slides go (the one
        // on screen is the home screen's own), so the music app isn't the one Android kills.
        if (level >= TRIM_MEMORY_RUNNING_LOW) Recs.get(this).trimSlides();
        if (level >= TRIM_MEMORY_UI_HIDDEN) {   // hidden: the app in front gets the memory
            Recs.get(this).trim();
            MusicArt.trim();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        lastKey = SystemClock.uptimeMillis();   // every key, also those the home screen takes (onUserInteraction misses them)
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
