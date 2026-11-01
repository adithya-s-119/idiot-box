package io.idiotbox;

import android.util.Log;

/** The app's log (adb logcat -s IdiotBox). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("IdiotBox", message);
    }
}
