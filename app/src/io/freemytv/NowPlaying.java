package io.freemytv;

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
 * What the TV's music apps are playing (Free My TV's music engine, any app with a media session): Android shows the sessions
 * only to an app with notification access, which this listener is (it reads no notifications). It runs in the :home
 * process and hands the session that plays to Music, where the home screen and the music screensaver read it.
 * Access, once, from a computer: adb shell cmd notification allow_listener io.freemytv/io.freemytv.NowPlaying
 */
public class NowPlaying extends NotificationListenerService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, new ComponentName(this, NowPlaying.class), main);
            watch(sessions.getActiveSessions(new ComponentName(this, NowPlaying.class)));
        } catch (SecurityException e) {
            AppLog.w("now playing: no notification access");
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        unwatch();
        Music.update(null);
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
                        pick();   // Free My TV's music engine: the songs either side, sent as extras
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
