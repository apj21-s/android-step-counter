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
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Runs only when needed: the phone-sensor sources (no Play services), or Max accuracy.
 * With the Recording API and Max accuracy off, nothing of Stride runs in the background.
 */
public class StepService extends Service {
    static final String CHANNEL = "steps";
    static final int NID = 1;
    private static StepService instance;
    private static long lastNotif;
    private StepEngine.Background engine;

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Start or stop to match the current settings. Only call from the app or at boot (Android limits background starts). */
    static void start(Context c) {
        if (!StepEngine.needsService(c)) {
            stop(c);
            return;
        }
        if (instance != null) return;
        try {
            c.startForegroundService(new Intent(c, StepService.class));
        } catch (IllegalStateException | SecurityException e) {
            // not allowed from the background right now; the next app open starts it
        }
    }

    static void stop(Context c) { c.stopService(new Intent(c, StepService.class)); }

    /** Redraw the notification, at most every 30 s unless forced. */
    static void refresh(Context c, boolean force) {
        StepService s = instance;
        if (s == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotif < 30_000) return;
        lastNotif = now;
        s.getSystemService(NotificationManager.class).notify(NID, build(s));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Step count", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            else startForeground(NID, build(this));
        } catch (RuntimeException e) {
            // Android 14+ refuses a health service without the activity permission
            stopSelf();
            return;
        }
        instance = this;
        engine = new StepEngine.Background(this);
        engine.start();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (engine != null) engine.stop();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    static Notification build(Context c) {
        long s = StepEngine.today(c);
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
