package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * Amazon Fire TV (Fire OS 7, Android 9; tested on a Fire TV Stick 3rd gen, AFTSSS). Fire OS protects its launcher
 * (pm disable-user refuses it) and ignores set-home-activity, so our home screen can't be the HOME activity there.
 * Instead FireTvService opens it whenever Amazon's home screen comes to the front, and LiveTvActivity turns the
 * remote's Live TV button into a straight way home.
 */
final class FireTv {
    private FireTv() {}

    /** Every Fire TV model is "AFT…" (AFTSSS, AFTMM, AFTKA…), made by Amazon. */
    static final boolean IS = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) && Build.MODEL.startsWith("AFT");

    static final String LAUNCHER = "com.amazon.tv.launcher";
    /** The Alexa assistant: Fire OS turns it back on at every boot, before our boot check can turn it off. */
    static final String ALEXA = "com.amazon.vizzini";

    private static final long DEBOUNCE_MS = 1500;
    private static long lastOpen;

    /** LiveTvActivity ships disabled (on Google TV it would take live TV links); turned on here on Fire TV only. */
    static void setUp(Context c) {
        if (!IS) return;
        ComponentName live = new ComponentName(c, LiveTvActivity.class);
        PackageManager pm = c.getPackageManager();
        if (pm.getComponentEnabledSetting(live) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(live, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
    }

    /** Opens our home screen. Twice within 1.5 s is once: Fire OS reports some windows more than once. */
    static void openHome(Context c, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpen < DEBOUNCE_MS) return;
        lastOpen = now;
        Intent home = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(c, HomeActivity.class))
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(home);
        AppLog.w("Fire TV: opened the home screen (" + reason + ")");
    }
}
