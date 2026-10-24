package io.freemytv;

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
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Brought back by the Music tab's player: stay as it was (not the Home button's fresh start). */
    static final String EXTRA_KEEP = "keep";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    // The music screensaver: after MusicSaverActivity.IDLE_MS without a key on the home screen while music plays.
    private final Handler idle = new Handler(Looper.getMainLooper());
    private long lastKey;
    private final Runnable checkIdle = new Runnable() {
        @Override public void run() {
            if (Music.playing() && hasWindowFocus() && SystemClock.uptimeMillis() - lastKey >= MusicSaverActivity.IDLE_MS) {
                startActivity(new Intent(HomeActivity.this, MusicSaverActivity.class));
            } else {
                idle.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
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
        idle.removeCallbacks(checkIdle);
        if (home != null) idle.postDelayed(checkIdle, 1000);
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
        idle.removeCallbacks(checkIdle);
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null && !intent.getBooleanExtra(EXTRA_KEEP, false)) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) Recs.get(this).trim();   // hidden: the app in front gets the memory
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        lastKey = SystemClock.uptimeMillis();   // every key, also those the home screen takes (onUserInteraction misses them)
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
