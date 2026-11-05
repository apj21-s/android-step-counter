package com.arco.stride;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or an app update: subscribe again (harmless if still subscribed), reschedule, restart. */
public class RefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            StepSyncJob.schedule(c);
            PendingResult pr = goAsync();
            StepRecordingManager.subscribe(c, () -> {
                StepService.start(c);
                pr.finish();
            });
        } else if (Intent.ACTION_TIMEZONE_CHANGED.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a)) {
            StepSyncJob.scheduleMidnight(c);       // "midnight" moved
            Widgets.updateAll(c);
        }
    }
}
