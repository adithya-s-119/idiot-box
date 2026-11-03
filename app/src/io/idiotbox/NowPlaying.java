package io.idiotbox;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * What the TV's music apps are playing (Idiot Box's music engine, any app with a media session): Android shows the
 * sessions only to an app with notification access, which this listener is (it reads no notifications). It runs in the
 * :home process and hands the session that plays to Music, where the home screen and the music screensaver read it.
 * Access, once, from a computer: adb shell cmd notification allow_listener io.idiotbox/io.idiotbox.NowPlaying
 *
 * It is bound only while the home screen or the music screensaver is in front: a video app playing in the background
 * updates its media session and notifications too often to listen to from here, and that jank is felt in the video.
 */
public class NowPlaying extends NotificationListenerService {
    private static volatile NowPlaying instance;
    private static volatile boolean wanted;
    /** Leaving the home for the music screensaver unbinds and rebinds within a moment: wait, and cancel if it comes back. */
    private static final long UNBIND_DELAY_MS = 3000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::sessionsChanged;
    private final Runnable unbind = () -> {
        if (!wanted && instance == this) {
            try {
                requestUnbind();
            } catch (RuntimeException ignored) {
            }
        }
    };

    /** Called by HomeActivity and MusicSaverActivity: listen only while one of them is in front. */
    static void active(boolean on) {
        wanted = on;
        NowPlaying n = instance;
        if (on) {
            if (n == null) {
                try {
                    NotificationListenerService.requestRebind(new ComponentName(Prefs.context(), NowPlaying.class));
                } catch (RuntimeException ignored) {
                }
            } else {
                n.main.removeCallbacks(n.unbind);
                n.apply();
            }
        } else if (n != null) {
            // Do not unbind at once: the music screensaver starts right after the home pauses, and its disconnect
            // would clear Music and close the screensaver. A few seconds of grace, cancelled by active(true).
            n.main.removeCallbacks(n.unbind);
            n.main.postDelayed(n.unbind, UNBIND_DELAY_MS);
        }
    }

    @Override public void onListenerConnected() {
        instance = this;
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions != null) {
            try {
                sessions.addOnActiveSessionsChangedListener(changed,
                        new ComponentName(this, NowPlaying.class), main);
            } catch (SecurityException e) {
                AppLog.w("now playing: no notification access");
            }
        }
        apply();
    }

    @Override public void onListenerDisconnected() {
        instance = null;
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        unwatch();
        Music.update(null);
        if (wanted) {
            // The home screen came back while this was unbinding (or Android dropped it): listen again.
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, NowPlaying.class));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void apply() {
        if (!wanted) {
            unwatch();
            Music.update(null);
            return;
        }
        if (sessions == null) return;
        try {
            watch(sessions.getActiveSessions(new ComponentName(this, NowPlaying.class)));
        } catch (SecurityException e) {
            AppLog.w("now playing: no notification access");
        }
    }

    private void sessionsChanged(List<MediaController> list) {
        if (wanted) watch(list);
    }

    private void watch(List<MediaController> list) {
        unwatch();
        if (list != null) {
            for (MediaController c : list) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) {
                        pick();
                    }

                    @Override public void onPlaybackStateChanged(PlaybackState s) {
                        pick();
                    }

                    @Override public void onExtrasChanged(android.os.Bundle extras) {
                        pick();   // Idiot Box's music engine: the songs either side, sent as extras
                    }

                    @Override public void onQueueChanged(List<MediaSession.QueueItem> queue) {
                        pick();   // an app sharing its queue: the songs after this one, known a little after it starts
                    }

                    @Override public void onSessionDestroyed() {
                        pick();
                    }
                };
                c.registerCallback(cb, main);
                watched.add(c);
                callbacks.add(cb);
            }
        }
        pick();
    }

    private void unwatch() {
        for (int i = 0; i < watched.size(); i++) watched.get(i).unregisterCallback(callbacks.get(i));
        watched.clear();
        callbacks.clear();
    }

    /** The session that plays (the first in Android's order), else the first with a song. */
    private void pick() {
        if (!wanted) return;
        MediaController best = null;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING && c.getMetadata() != null) {
                best = c;
                break;
            }
            if (best == null && c.getMetadata() != null) best = c;
        }
        Music.update(best);
    }
}
