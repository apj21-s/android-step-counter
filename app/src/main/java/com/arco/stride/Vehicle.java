package com.arco.stride;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * "Am I in a vehicle?" from Google's Activity Recognition (transition API, IN_VEHICLE enter/exit).
 * Events arrive late but carry the time they happened (same clock as sensor timestamps), so bouts
 * ask at close time. Keeps the last 20 transitions in preferences as "1:ns,0:ns,..." (1 = entered).
 */
public final class Vehicle extends BroadcastReceiver {
    private static final int KEEP = 20;

    /** Subscribe (safe to call again). Marks "arOn" when Play services accepted the request. */
    static void start(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = StepStore.prefs(app);
        if (!StepRecordingManager.available(app)
                || app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            p.edit().putBoolean("arOn", false).apply();
            return;
        }
        List<ActivityTransition> t = new ArrayList<>();
        t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
        t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
        try {
            ActivityRecognition.getClient(app).requestActivityTransitionUpdates(new ActivityTransitionRequest(t), intent(app))
                    .addOnSuccessListener(v -> p.edit().putBoolean("arOn", true).apply())
                    .addOnFailureListener(e -> p.edit().putBoolean("arOn", false).apply());
        } catch (SecurityException e) {
            p.edit().putBoolean("arOn", false).apply();
        }
    }

    static void stop(Context c) {
        Context app = c.getApplicationContext();
        try {
            ActivityRecognition.getClient(app).removeActivityTransitionUpdates(intent(app));
        } catch (SecurityException ignored) {
            // permission gone: nothing to remove
        }
        StepStore.prefs(app).edit().putBoolean("arOn", false).apply();
    }

    private static PendingIntent intent(Context app) {
        // must be mutable: Play services adds the transition result to it
        return PendingIntent.getBroadcast(app, 7, new Intent(app, Vehicle.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    static boolean available(Context c) { return StepStore.prefs(c).getBoolean("arOn", false); }

    /** Was the phone in a vehicle at `ns` (sensor clock)? Uses the last transition at or before it. */
    static boolean at(Context c, long ns) {
        return at(StepStore.prefs(c).getString("arEvents", ""), ns);
    }

    static boolean at(String events, long ns) {
        long best = Long.MIN_VALUE;
        boolean in = false;
        for (String e : events.split(",")) {
            int k = e.indexOf(':');
            if (k < 0) continue;
            long t = Long.parseLong(e.substring(k + 1));
            if (t <= ns && t > best) { best = t; in = e.charAt(0) == '1'; }
        }
        return in;
    }

    @Override public void onReceive(Context c, Intent i) {
        if (!ActivityTransitionResult.hasResult(i)) return;
        ActivityTransitionResult r = ActivityTransitionResult.extractResult(i);
        if (r == null) return;
        SharedPreferences p = StepStore.prefs(c);
        List<String> list = new ArrayList<>();
        for (String e : p.getString("arEvents", "").split(",")) if (!e.isEmpty()) list.add(e);
        for (ActivityTransitionEvent e : r.getTransitionEvents()) {
            if (e.getActivityType() != DetectedActivity.IN_VEHICLE) continue;
            boolean enter = e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER;
            list.add((enter ? "1:" : "0:") + e.getElapsedRealTimeNanos());
        }
        while (list.size() > KEEP) list.remove(0);
        p.edit().putString("arEvents", String.join(",", list)).apply();
    }
}
