package com.arco.stride;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;


/**
 * One place that listens to the sensors, shared by the app screen and the background service.
 *
 *  - Step counter (hardware running total): the base count, saved in StepStore.
 *  - Step detector (one event per step, fast): shown right away while the screen is on,
 *    so the number moves step by step instead of in batches.
 *  - Accelerometer ("Max accuracy", on by default, screen on or off): AccelPedometer finds
 *    steps the chip misses when the phone is held steady; StepFusion decides what to add.
 */
final class StepEngine implements SensorEventListener {
    interface Listener { void onSteps(long display); }

    private static StepEngine engine;
    static Listener listener;                       // the app screen, when visible

    private final Context app;
    private final SensorManager sm;
    private final Sensor counter, detector, accelFast, accelWake;
    private final AccelPedometer pedo = new AccelPedometer();
    private PowerManager.WakeLock wake;
    private int clients;
    private boolean started, screenOn, accelActive;

    private long provisional, lastDetectorMs;               // detector steps not yet in the count
    private final StepFusion fusion = new StepFusion();
    private long lastEvalNs;
    private long chipToday, motionToday, addedToday;
    private String statsDay = "";
    private long lastWidget, lastNotif;

    private StepEngine(Context c) {
        app = c.getApplicationContext();
        sm = app.getSystemService(SensorManager.class);
        counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        accelFast = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelWake = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);   // wakes the phone to deliver, if it has one
    }

    private static StepEngine get(Context c) {
        if (engine == null) engine = new StepEngine(c);
        return engine;
    }

    // ---------- public API ----------

    /** Steps to show: saved count plus detector steps the chip hasn't reported yet. */
    static long display(Context c) {
        long saved = StepStore.steps(c);
        return engine == null ? saved : saved + engine.provisional;
    }

    static long displayTotal(Context c) {
        long saved = StepStore.prefs(c).getLong("total", 0);
        return engine == null ? saved : saved + engine.provisional;
    }

    /** Debug line for the 100-step test. */
    static String debug() {
        StepEngine e = engine;
        if (e == null) return "";
        e.stats();
        return "Today: chip " + e.chipToday + " · motion " + e.motionToday + " · added " + e.addedToday;
    }

    static void acquire(Context c) { StepEngine e = get(c); e.clients++; e.ensure(); }

    static void release(Context c) {
        StepEngine e = get(c);
        if (e.clients > 0 && --e.clients == 0 && e.started) e.stop();
    }

    /** Start if someone needs us and we couldn't start before (e.g. permission just granted). */
    static void ensure(Context c) { get(c).ensure(); }

    /** Re-read settings (Max accuracy on/off). */
    static void reconfigure(Context c) { StepEngine e = get(c); if (e.started) e.register(); }

    // ---------- lifecycle ----------

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { setScreen(Intent.ACTION_SCREEN_ON.equals(i.getAction())); }
    };

    private void ensure() {
        if (started || clients == 0 || counter == null || !StepService.hasPermission(app)) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(screen, f);
        screenOn = app.getSystemService(PowerManager.class).isInteractive();
        register();
    }

    private void stop() {
        started = false;
        sm.unregisterListener(this);
        releaseWake();
        try { app.unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        provisional = 0;
        fusion.reset();
        pedo.reset();
    }

    private void setScreen(boolean on) {
        if (on == screenOn) return;
        screenOn = on;
        provisional = 0;
        register();
    }

    private void register() {
        sm.unregisterListener(this);
        releaseWake();
        boolean boost = StepStore.boost(app);
        accelActive = boost && accelFast != null;
        if (!accelActive) fusion.reset();
        // With motion checking on, the chip must report promptly so windows line up.
        sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, boost || screenOn ? 0 : 10_000_000);
        if (screenOn && detector != null) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_FASTEST);
        if (!boost || accelFast == null) return;
        if (screenOn) {
            sm.registerListener(this, accelFast, 20_000);                  // 50 per second
        } else if (accelWake != null) {
            sm.registerListener(this, accelWake, 20_000, 4_000_000);       // 50 per second, delivered every ~4 s
        } else {
            holdWake();                                                     // no wake-up sensor: keep the CPU awake
            sm.registerListener(this, accelFast, 20_000);
        }
    }

    private void holdWake() {
        if (wake == null) {
            wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stride:motion");
            wake.setReferenceCounted(false);
        }
        wake.acquire();
    }

    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }

    // ---------- sensors ----------

    @Override public void onSensorChanged(SensorEvent e) {
        long ts = e.timestamp;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_STEP_COUNTER: {
                long delta = StepStore.onRaw(app, (long) e.values[0]);
                if (delta > 0) {
                    stats();
                    chipToday += delta;
                    provisional = Math.max(0, provisional - delta);
                    if (SystemClock.elapsedRealtime() - lastDetectorMs > 3000) provisional = 0;
                    changed();
                }
                if (accelActive) fusion.chip(Math.max(0, delta), ts);
                break;
            }
            case Sensor.TYPE_STEP_DETECTOR:
                if (screenOn) {
                    provisional++;
                    lastDetectorMs = SystemClock.elapsedRealtime();
                    changed();
                }
                break;
            case Sensor.TYPE_ACCELEROMETER: {
                int n = pedo.onSample(e.values[0], e.values[1], e.values[2], ts);
                if (n > 0) {
                    stats();
                    motionToday += n;
                }
                fusion.motion(n, ts);
                if (ts - lastEvalNs > 5_000_000_000L) {
                    lastEvalNs = ts;
                    long change = fusion.evaluate(ts);
                    if (change != 0) { StepStore.addSteps(app, change); stats(); addedToday += change; changed(); }
                }
                break;
            }
            default: break;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void stats() {
        String d = StepStore.today();
        if (!d.equals(statsDay)) { statsDay = d; chipToday = motionToday = addedToday = 0; }
    }

    private void changed() {
        long d = display(app);
        if (listener != null) listener.onSteps(d);
        long now = SystemClock.elapsedRealtime();
        if (now - lastWidget > (screenOn ? 3_000 : 15_000)) { lastWidget = now; Widgets.updateAll(app); }
        if (now - lastNotif > (screenOn ? 2_000 : 5_000)) { lastNotif = now; StepService.refresh(); }
    }
}
