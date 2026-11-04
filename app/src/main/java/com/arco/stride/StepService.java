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

import java.util.Locale;

/** Keeps the step sensor registered so widgets stay live. Shows a quiet notification. */
public class StepService extends Service {
    static final String CHANNEL = "steps";
    static final int NID = 1;
    private static StepService instance;


    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    static void start(Context c) {
        if (!hasPermission(c) || !StepStore.live(c)) return;
        try { c.startForegroundService(new Intent(c, StepService.class)); }
        catch (RuntimeException ignored) { /* not allowed from background right now; alarm keeps widgets fresh */ }
    }

    static void stop(Context c) { c.stopService(new Intent(c, StepService.class)); }

    /** Redraw the notification now (after settings change or midnight). */
    static void refresh() {
        if (instance != null) instance.getSystemService(NotificationManager.class).notify(NID, build(instance));
    }

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
        StepEngine.acquire(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (instance == this) { instance = null; StepEngine.release(this); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    static Notification build(Context c) {
        long s = StepEngine.display(c);
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
