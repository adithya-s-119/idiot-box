package io.idiotbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fire OS 6: starts the UsageStats keeper again after a reboot, if Idiot Box mode is on. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!FireTvKeeper.needed()) return;   // Fire OS 7/8 use the accessibility service; the receiver is off there
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && HomeMode.freeActive(c)) {
            FireTvKeeper.start(c);
        }
    }
}
