package com.arco.stride;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Locale;

/** Keeps the step sensor registered so widgets stay live. Shows a quiet notification. */
public class StepService extends Service implements SensorEventListener {
    static final String CHANNEL = "steps";
    static final int NID = 1;
    private static StepService instance;
    private static long lastWidget;
    private SensorManager sm;
    private long lastNotif;

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    static void start(Context c) {
        if (!hasPermission(c) || !StepStore.live(c)) return;
        try { c.startForegroundService(new Intent(c, StepService.class)); }
        catch (RuntimeException ignored) { /* not allowed from background right now; alarm keeps widgets fresh */ }
    }

    static void stop(Context c) { c.stopService(new Intent(c, StepService.class)); }

    /** Feed a sensor value from anywhere; refreshes widgets at most every 15 s. */
    static void ingest(Context c, long raw) {
        if (!StepStore.onRaw(c, raw)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWidget > 15_000) { lastWidget = now; Widgets.updateAll(c); }
        if (instance != null) instance.maybeNotify(false);
    }

    /** Redraw the notification now (after settings change or midnight). */
    static void refresh() { if (instance != null) instance.maybeNotify(true); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Step count", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            else startForeground(NID, build(this));
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }
        instance = this;
        sm = getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) { stopSelf(); return; }
        // Batch readings for up to 10 s: low battery use, nothing is lost (the sensor keeps a running total).
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onSensorChanged(SensorEvent e) { ingest(this, (long) e.values[0]); }
    @Override public void onAccuracyChanged(Sensor s, int a) { }
    @Override public IBinder onBind(Intent i) { return null; }

    private void maybeNotify(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotif < 5_000) return;
        lastNotif = now;
        getSystemService(NotificationManager.class).notify(NID, build(this));
    }

    static Notification build(Context c) {
        long s = StepStore.steps(c);
        int goal = StepStore.goal(c);
        int pct = (int) Math.floor(s * 100.0 / goal);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(String.format(Locale.US, "%,d steps", s))
                .setContentText(String.format(Locale.US, "%d%% of %,d", pct, goal))
                .setProgress(100, Math.min(100, pct), false)
                .setColor(StepStore.a1(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build();
    }
}
