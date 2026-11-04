package com.arco.stride;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Runs about every 15 minutes and after boot: rolls the day over and refreshes widgets. */
public class RefreshReceiver extends BroadcastReceiver {
    static final String ACTION = "com.arco.stride.REFRESH";

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, RefreshReceiver.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    @Override public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(c);
            StepService.start(c);
        }
        StepService.start(c);                     // watchdog: no-op if already running
        if (StepStore.rollIfNeeded(c)) StepService.refresh();

        // Live mode: the service already listens. Otherwise take one quick reading.
        if (StepStore.live(c) || !StepService.hasPermission(c)) { Widgets.updateAll(c); return; }
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) { Widgets.updateAll(c); return; }
        PendingResult pr = goAsync();
        boolean[] done = {false};
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (done[0]) return;
                done[0] = true;
                sm.unregisterListener(this);
                StepStore.onRaw(c, (long) e.values[0]);
                Widgets.updateAll(c);
                pr.finish();
            }
            @Override public void onAccuracyChanged(Sensor x, int y) { }
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            sm.unregisterListener(l);
            Widgets.updateAll(c);
            pr.finish();
        }, 4000);
    }
}
