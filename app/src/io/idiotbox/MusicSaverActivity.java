package io.idiotbox;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The music screensaver (MusicSaverView), opened by the home screen after IDLE_MS without a key while music plays.
 * OK pauses and plays, Left and Right ask the app for the song before and after (as on Klangbild); any other key goes
 * back to the home screen. It keeps the screen on while it shows; after STOPPED_MS without music it closes, and the
 * TV's own screensaver takes over in time.
 */
public class MusicSaverActivity extends Activity implements Music.Listener {
    static final long IDLE_MS = 15_000;
    private static final long STOPPED_MS = 5 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (Music.track() == null || Music.stoppedFor() > STOPPED_MS) finish();
            else handler.postDelayed(this, 10_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new MusicSaverView(this));
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onStart() {
        super.onStart();
        Music.addListener(this);
        handler.post(check);
    }

    @Override public void musicChanged() {
        if (Music.track() == null) finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) Music.playPause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) Music.previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) Music.next();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);   // the TV's own volume
            default:
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
        }
    }

    /** Gone from the screen (Home, another app): the home screen next time, not this. */
    @Override protected void onStop() {
        super.onStop();
        Music.removeListener(this);
        handler.removeCallbacks(check);
        finish();
    }
}
