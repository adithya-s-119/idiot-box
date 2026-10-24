package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Fire TV only (see FireTv). When Amazon's home screen comes to the front, opens ours; when the Alexa overlay opens,
 * closes it. The Home button never reaches accessibility services on Fire OS, and Android holds back activity starts
 * for 5 s after it, so Amazon's screen is covered at once by a black overlay (overlays aren't held back) until ours is
 * up. Reads window content only to see whether Alexa's window is still open.
 */
public class FireTvService extends AccessibilityService {
    private static final String HOME_ACTIVITY = "HomeActivity";
    private static final long COVER_MS = 8000;
    private static final long ALEXA_RETRY_MS = 200;
    private static final int ALEXA_MAX_BACKS = 8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = new Runnable() {
        @Override public void run() { uncover(); }
    };
    // The overlay ignores Back until it has focus, so Back goes only while its window is still there: no stray Back
    // reaches the app underneath.
    private final Runnable closeAlexa = new Runnable() {
        @Override public void run() {
            boolean open = alexaOpen();
            if (open && alexaBacks < ALEXA_MAX_BACKS) {
                alexaBacks++;
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(this, ALEXA_RETRY_MS);
                return;
            }
            AppLog.w("Fire TV: Alexa " + (open ? "still open" : "closed") + " after " + alexaBacks + " back(s)");
            alexaClosing = false;
        }
    };
    private int alexaBacks;
    private boolean alexaClosing;
    private View cover;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!FireTv.IS || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null) return;
        if (FireTv.LAUNCHER.contentEquals(pkg)) {
            if (cls != null && cls.toString().contains(HOME_ACTIVITY) && active()) {
                cover();
                FireTv.openHome(this, "Amazon home");
            }
        } else if (FireTv.ALEXA.contentEquals(pkg)) {
            if (!alexaClosing && active()) {
                alexaClosing = true;
                alexaBacks = 0;
                handler.postDelayed(closeAlexa, ALEXA_RETRY_MS);
            }
        } else if (HomeMode.PKG.contentEquals(pkg) && cls != null && cls.toString().startsWith(HomeMode.PKG + ".")) {
            uncover();   // one of our screens is up (not the cover's own window)
        }
    }

    /**
     * Only in Free My TV mode, and not after the watchdog gave the home screen back: on Fire TV the service stays on
     * (no on-device ADB to turn it off), so Original and the watchdog work through the mode.
     */
    private boolean active() {
        org.json.JSONObject s = HomeMode.state(this);
        return HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        uncover();
        super.onDestroy();
    }

    private boolean alexaOpen() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            boolean alexa = FireTv.ALEXA.contentEquals(String.valueOf(active.getPackageName()));
            active.recycle();
            if (alexa) return true;
        }
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            boolean alexa = FireTv.ALEXA.contentEquals(String.valueOf(root.getPackageName()));
            root.recycle();
            if (alexa) return true;
        }
        return false;
    }

    /** Black, not focusable, not touchable: keys go on to the screen underneath. Gone when ours is up or after 8 s. */
    private void cover() {
        View view = new View(this);
        view.setBackgroundColor(Color.BLACK);
        cover(view);
    }

    private void cover(View view) {
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MS);
        if (cover != null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, params);
            cover = view;
        } catch (RuntimeException e) {
            AppLog.w("Fire TV: couldn't cover Amazon's home screen: " + e.getMessage());
        }
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (RuntimeException ignored) {}
        cover = null;
    }
}
