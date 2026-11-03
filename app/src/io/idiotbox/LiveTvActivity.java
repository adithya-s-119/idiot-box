package io.idiotbox;

import android.app.Activity;
import android.os.Bundle;

/**
 * The remote's Live TV button opens livetv:// links meant for Amazon's Live TV app, which Idiot Box mode turns off.
 * With it off, this is the only app that takes them, so the button opens our home screen at once (no 5 s wait: that
 * is only after Home).
 */
public class LiveTvActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FireTv.openHome(this, "Live TV button");
        finish();
    }
}
