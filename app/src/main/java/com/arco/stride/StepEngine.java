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

import java.util.TreeMap;

/**
 * One place that listens to the sensors, shared by the app screen and the background service.
 *
 *  - Step counter (hardware running total): the base count, saved in StepStore.
 *  - Step detector (one event per step, fast): shown right away while the screen is on,
 *    so the number moves step by step instead of in batches.
 *  - Accelerometer ("Max accuracy", on by default, screen on or off): our own detector.
 *    The chip misses steps when the phone is held steady (no arm swing). Every 15 seconds
 *    of walking we compare both, using when each step happened, and add the steps the chip
 *    missed. Windows are judged 20 s late so slow chip reports aren't double-counted.
 */
final class StepEngine implements SensorEventListener {
    interface Listener { void onSteps(long display); }

    private static StepEngine engine;
    static Listener listener;                       // the app screen, when visible

    private static final int WINDOW_S = 15;         // compare chip vs motion in 15 s windows
    private static final int LAG_S = 20;            // ...once the chip has had 20 s to report
    private static final int MIN_WALK = 10;         // at least 10 motion steps = walking

    private final Context app;
    private final SensorManager sm;
    private final Sensor counter, detector, accelFast, accelWake;
    private final AccelPedometer pedo = new AccelPedometer();
    private PowerManager.WakeLock wake;
    private int clients;
    private boolean started, screenOn, accelActive;

    private long provisional, lastDetectorMs;               // detector steps not yet in the count
    private final TreeMap<Long, long[]> secs = new TreeMap<>(); // second -> {motion, chip}
    private long evalFrom = -1, lastCounterNs, lastEvalNs, lastAdded;
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
        secs.clear(); evalFrom = -1; lastCounterNs = 0; lastAdded = 0;
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
        if (!accelActive) { secs.clear(); evalFrom = -1; lastAdded = 0; }
        // With motion checking on, the chip must report promptly so windows line up.
        sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, boost || screenOn ? 0 : 10_000_000);
        if (screenOn && detector != null) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_FASTEST);
        if (!boost || accelFast == null) return;
        if (screenOn) {
            sm.registerListener(this, accelFast, 20_000);                  // 50 per second
        } else if (accelWake != null) {
            sm.registerListener(this, accelWake, 40_000, 4_000_000);       // 25 per second, delivered every ~4 s
        } else {
            holdWake();                                                     // no wake-up sensor: keep the CPU awake
            sm.registerListener(this, accelFast, 40_000);
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
                    spreadChip(delta, ts);
                    provisional = Math.max(0, provisional - delta);
                    if (SystemClock.elapsedRealtime() - lastDetectorMs > 3000) provisional = 0;
                    changed();
                }
                lastCounterNs = ts;
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
                if (evalFrom < 0) evalFrom = sec(ts);
                int n = pedo.onSample(e.values[0], e.values[1], e.values[2], ts);
                if (n > 0) {
                    stats();
                    motionToday += n;
                    bucket(sec(ts))[0] += n;
                }
                if (ts - lastEvalNs > 5_000_000_000L) { lastEvalNs = ts; evaluate(ts); }
                break;
            }
            default: break;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private static long sec(long ns) { return ns / 1_000_000_000L; }

    private long[] bucket(long s) {
        long[] v = secs.get(s);
        if (v == null) { v = new long[2]; secs.put(s, v); }
        return v;
    }

    /** The counter reports "N more steps, the last one at time ts": spread them over when they happened. */
    private void spreadChip(long delta, long ts) {
        if (!accelActive) return;
        long s1 = sec(ts), s0;
        if (lastCounterNs == 0 || ts - lastCounterNs > 60_000_000_000L) {
            s0 = s1 - Math.min(30, Math.max(0, (delta + 1) / 2 - 1));       // after a pause: assume ~2 steps per second
        } else {
            s0 = Math.min(s1, sec(lastCounterNs) + 1);
        }
        long span = s1 - s0 + 1, each = delta / span, rest = delta % span;
        for (long s = s0; s <= s1; s++) bucket(s)[1] += each + (s - s0 < rest ? 1 : 0);
    }

    /** Judge every finished 15 s window: add steps the chip missed. */
    private void evaluate(long nowNs) {
        long nowS = sec(nowNs);
        while (evalFrom >= 0 && nowS - LAG_S >= evalFrom + WINDOW_S) {
            long motion = 0, chip = 0;
            for (long[] v : secs.subMap(evalFrom, evalFrom + WINDOW_S).values()) { motion += v[0]; chip += v[1]; }
            long diff = motion - chip;
            // Steps can land in the next window: give back what we added last time if the chip was just late.
            if (diff < 0 && lastAdded > 0) {
                long back = Math.min(-diff, lastAdded);
                StepStore.addSteps(app, -back);
                addedToday -= back;
            }
            long add = 0;
            boolean walking = motion >= MIN_WALK && motion <= WINDOW_S * 3.4;     // 10 .. ~200 steps per minute
            boolean doubled = chip >= motion * 0.45 && motion > chip * 1.7;       // looks like 2 peaks per step
            if (walking && !doubled && diff > 0) add = diff;
            if (add > 0) { StepStore.addSteps(app, add); stats(); addedToday += add; changed(); }
            lastAdded = add;
            evalFrom += WINDOW_S;
        }
        secs.headMap(evalFrom - 60).clear();
    }

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

    /**
     * Step detection from the accelerometer: follow the bounce in total acceleration,
     * count peaks, and only start counting after 6 evenly spaced steps (so a shake, a bump
     * or a car ride doesn't count as walking).
     */
    static final class AccelPedometer {
        private float gravity, smooth, prev, prev2, amp = 1.2f, meanDt = 0.55f;
        private boolean init;
        private long lastNs, lastPeak;
        private int regular;

        void reset() { init = false; regular = 0; lastPeak = 0; amp = 1.2f; meanDt = 0.55f; }

        int onSample(float x, float y, float z, long tNs) {
            float m = (float) Math.sqrt(x * x + y * y + z * z);
            if (!init || tNs - lastNs > 1_000_000_000L || tNs <= lastNs) {   // first sample or a gap: start over
                gravity = m; smooth = 0; prev = prev2 = 0; init = true; lastNs = tNs; regular = 0; lastPeak = 0;
                return 0;
            }
            float dt = (tNs - lastNs) / 1e9f;
            lastNs = tNs;
            gravity += (1 - (float) Math.exp(-2 * Math.PI * 0.2 * dt)) * (m - gravity);        // ~0.2 Hz: gravity
            smooth += (1 - (float) Math.exp(-2 * Math.PI * 3.0 * dt)) * ((m - gravity) - smooth); // ~3 Hz: step bounce
            int steps = 0;
            float thr = Math.max(0.45f, 0.45f * amp);
            if (prev > prev2 && prev >= smooth && prev > thr) {                  // prev sample was a peak
                double gap = lastPeak == 0 ? -1 : (tNs - lastPeak) / 1e9;
                if (gap < 0 || gap >= 0.28) {
                    boolean even = gap >= 0.28 && gap <= 1.3 && (regular < 2 || Math.abs(gap - meanDt) < 0.4 * meanDt);
                    if (even) {
                        regular++;
                        meanDt = regular <= 2 ? (float) gap : 0.75f * meanDt + 0.25f * (float) gap;
                        if (regular == 6) steps = 6;          // walking confirmed: count the first 6 too
                        else if (regular > 6) steps = 1;
                    } else {
                        regular = 1;
                    }
                    amp = 0.8f * amp + 0.2f * prev;
                    lastPeak = tNs;
                }
            }
            if (lastPeak != 0 && (tNs - lastPeak) / 1e9 > 1.6) regular = 0;       // stopped walking
            prev2 = prev;
            prev = smooth;
            return steps;
        }
    }
}
