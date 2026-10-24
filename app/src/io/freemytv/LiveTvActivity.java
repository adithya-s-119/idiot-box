package io.freemytv;

import android.app.Activity;
import android.os.Bundle;

/**
 * Fire TV only: the remote's Live TV button opens livetv:// links meant for Amazon's Live TV app, which Free My TV
 * mode turns off. With it off, this is the only app that takes them, so the button opens our home screen at once
 * (no 5 s wait: that is only after Home). Disabled in the manifest; FireTv.setUp enables it on Fire TV.
 */
public class LiveTvActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FireTv.IS) FireTv.openHome(this, "Live TV button");
        finish();
    }
}
