package io.idiotbox;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;

/**
 * Fire OS 6 only (Android 7.1, API 25): that Fire OS refuses third-party accessibility services, so FireTvService never
 * binds. This foreground service reads the screen in front from UsageStats and brings our home screen's task to the
 * front when Amazon's home appears. moveTaskToFront is not an activity start, so Fire OS's post-Home launch delay does
 * not apply. tools/firetv.sh grants the UsageStats app-op from a computer.
 */
public class FireTvKeeper extends Service {
    private static final String CHANNEL = "home";
    private static final int NOTIFICATION = 1;
    private static final long POLL_MS = 1000;
    private static final long WINDOW_MS = 3_000;
    /** The first look also sees the launcher that was already in front when the service started (after a reboot). */
    private static final long FIRST_WINDOW_MS = 5 * 60_000;
    private static final long AGAIN_MS = 1_500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private UsageStatsManager stats;
    private long lastMove;
    private boolean first = true;
    private boolean warned;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!HomeMode.freeActive(FireTvKeeper.this)) { stopSelf(); return; }
            look();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Only Fire OS 6 needs this; Fire OS 7 and 8 run FireTvService. */
    static boolean needed() {
        return FireTv.IS && Build.VERSION.SDK_INT <= 27;
    }

    static void start(Context c) {
        if (!needed()) return;
        Intent intent = new Intent(c, FireTvKeeper.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent);
        else c.startService(intent);
    }

    static void stop(Context c) {
        if (!needed()) return;
        c.stopService(new Intent(c, FireTvKeeper.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        stats = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        startForeground(NOTIFICATION, notification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    /** The last screen that came to the front; only Amazon's own home activity counts. */
    private void look() {
        if (!usageAllowed()) {
            if (!warned) {
                warned = true;
                AppLog.w("Fire TV: Usage access is off; run tools/firetv.sh <ip> setup");
            }
            return;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = stats.queryEvents(now - (first ? FIRST_WINDOW_MS : WINDOW_MS), now);
        first = false;
        if (events == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = e.getPackageName();
                cls = e.getClassName();
            }
        }
        if (!FireTv.LAUNCHER.equals(pkg) || cls == null || !cls.contains("HomeActivity")) return;
        long at = SystemClock.elapsedRealtime();
        if (at - lastMove < AGAIN_MS) return;
        lastMove = at;
        if (frontHome()) AppLog.w("Fire TV: brought the home screen to the front (UsageStats)");
        else FireTv.openHome(this, "Amazon home (UsageStats)");
    }

    /** Our home task is usually still there: moving it is instant and skips Fire OS's post-Home delay. */
    private boolean frontHome() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.AppTask> tasks = am.getAppTasks();
        if (tasks != null) {
            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                Intent base = info == null ? null : info.baseIntent;
                ComponentName cn = base == null ? null : base.getComponent();
                if (cn != null && HomeActivity.class.getName().equals(cn.getClassName())) {
                    task.moveToFront();
                    return true;
                }
            }
        }
        // Older Fire OS builds may not list a task excluded from Recents: ask for our running tasks instead.
        List<ActivityManager.RunningTaskInfo> running = am.getRunningTasks(20);
        if (running != null) {
            for (ActivityManager.RunningTaskInfo info : running) {
                ComponentName base = info.baseActivity;
                if (base != null && HomeActivity.class.getName().equals(base.getClassName())) {
                    am.moveTaskToFront(info.id, 0);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean usageAllowed() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private Notification notification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.keeper_channel), NotificationManager.IMPORTANCE_MIN));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        return builder.setSmallIcon(R.drawable.sym_home)
                .setContentTitle(getString(R.string.keeper_title))
                .setContentText(getString(R.string.keeper_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
