package com.arco.stride;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decides where steps come from and runs the sensors.
 *
 * Source (first one that works):
 *   recording - Google Recording API. Play services counts; Stride needs nothing running.
 *   counter   - phone step counter, batched 30 s, in the background service (no wake lock).
 *   detector  - phone step detector, batched 30 s.
 *   accel     - our motion detector, always on (last resort, only on phones with no step sensor).
 *
 * Max accuracy (Settings, on by default): the accelerometer stays off until a step wakes us
 * (wake-up step detector, or significant motion). It then runs at 25 Hz for the walking bout,
 * with the proximity sensor (where is the phone?). StepFusion picks chip or motion by position,
 * and takes chip steps back when Activity Recognition says it was a vehicle. Motion = MotionCounter
 * peaks, plus its rhythm count for a steady hand (or StepNet, see HAND_MOTION_NET). Off 60 s after the last step.
 * Steady-hand bouts where chip and motion agree teach the detector how strongly this person's
 * phone bounces (limited, slow, resettable in Settings). Step timing is never learned.
 *
 * Live: while the app is visible the step counter reports instantly, for display only.
 */
final class StepEngine {
    interface Listener { void onSteps(long today); }

    private static Live live;

    /** Learned hand bounce (m/s², median step peak) is kept inside these limits. */
    static final float HAND_PEAK_MIN = 0.15f, HAND_PEAK_MAX = 3f;

    /** Forget what agreeing bouts taught (Settings). Calibration is reset separately. */
    static void resetLearning(Context c) {
        StepStore.prefs(c).edit().remove("userHandPeak").remove("userHandBouts").remove("userPeriod").apply();
    }

    /**
     * Motion source: the step model (true) or the peak counter + rhythm (false). On the Cambridge
     * walks (people the model never saw) the model was less accurate than the peak counter, so it
     * stays off here; Lab mode still shows its count next to the others. See tools/real/README.md.
     */
    static final boolean HAND_MOTION_NET = false;

    private StepEngine() {}

    static String source(Context c) {
        if (StepRecordingManager.active(c)) return "recording";
        SensorManager sm = c.getSystemService(SensorManager.class);
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) return "counter";
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) return "detector";
        if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) return "accel";
        return "none";
    }

    /** The background service is needed for the phone-sensor sources, or for Max accuracy. */
    static boolean needsService(Context c) {
        if (!StepService.hasPermission(c)) return false;
        String s = source(c);
        if (s.equals("none")) return false;
        return !s.equals("recording") || StepStore.boost(c);
    }

    /** Today's steps: stored total, or the live count if the app just showed more. */
    static long today(Context c) {
        String date = LocalDate.now().toString();
        long stored = StepRepository.get(c).day(date);
        SharedPreferences p = StepStore.prefs(c);
        if (live != null) return Math.max(stored, live.today());
        if (date.equals(p.getString("liveDate", ""))) return Math.max(stored, p.getLong("liveSteps", 0));
        return stored;
    }

    static void startLive(Context c, Listener l) {
        if (live != null) live.stop();
        live = new Live(c, l);
    }

    static void stopLive() {
        if (live != null) live.stop();
        live = null;
    }

    /** Step-counter steps since the app was opened (for the 100-step test), or -1. */
    static long liveCounter() { return live == null ? -1 : live.counterSteps(); }

    static void startCalibration() { if (live != null) live.startCalibration(); }

    /** Returns {motion steps, median peak height} for the calibration walk. */
    static float[] stopCalibration() { return live == null ? new float[]{0, 0} : live.stopCalibration(); }

    static long wallMs(long sensorNs) {
        return System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - sensorNs) / 1_000_000L;
    }

    // =====================================================================================
    /** Runs inside StepService. */
    static final class Background extends TriggerEventListener implements SensorEventListener {
        private final Context app;
        private final SensorManager sm;
        private final StepRepository repo;
        private final Sensor counter, detector, wakeDetector, sigMotion, accel, wakeAccel;
        private final String source;
        private final boolean boost;
        private final MotionCounter pedo = new MotionCounter();
        private final StepNet net = StepNet.standard();
        private final Placement place = new Placement();
        private final StepFusion fusion = new StepFusion();
        private final Sensor proximity;
        private int periodN;
        private final Handler main = new Handler(Looper.getMainLooper());
        private PowerManager.WakeLock wake;
        private boolean accelOn, detectorForChip;
        private long lastRaw = -1, lastTickNs, lastWidget;

        Background(Context c) {
            app = c.getApplicationContext();
            sm = app.getSystemService(SensorManager.class);
            repo = StepRepository.get(app);
            counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            Sensor wd = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true);
            wakeDetector = wd != null && wd.isWakeUpSensor() ? wd : null;
            sigMotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor wa = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
            wakeAccel = wa != null && wa.isWakeUpSensor() ? wa : null;
            proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            source = source(app);
            boost = StepStore.boost(app) && !source.equals("accel") && accel != null;
            SharedPreferences p = StepStore.prefs(app);
            fusion.gain = p.getFloat("calGain", 1f);
            learned(p);
        }

        /**
         * Detector settings from calibration (a hand-counted walk), or else from what agreeing bouts
         * taught us. Only the signal strength is learned, and only within fixed limits: chip and motion
         * agreeing isn't proof they're right, so nothing that changes how steps are timed is learned.
         */
        private void learned(SharedPreferences p) {
            float handPeak = p.getFloat("userHandPeak", 0);
            if (p.contains("calFloor")) pedo.floor = p.getFloat("calFloor", 0.12f);
            else if (handPeak > 0) pedo.floor = Math.max(0.05f, Math.min(0.12f, 0.3f * handPeak));
            else pedo.floor = 0.12f;
        }

        void start() {
            if (source.equals("counter")) registerCounter(false);
            if (source.equals("detector")) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000);
            if (source.equals("accel")) startAccel();
            if (boost) {
                armTrigger();
                Vehicle.start(app);
            }
        }

        void stop() {
            sm.unregisterListener(this);
            if (sigMotion != null) sm.cancelTriggerSensor(this, sigMotion);
            main.removeCallbacksAndMessages(null);
            releaseWake();
            accelOn = false;
        }

        // ----- sensors -----

        private void registerCounter(boolean fast) {
            if (counter == null) return;
            sm.unregisterListener(this, counter);
            // fast during a bout (so chip steps line up with the bout), batched 30 s otherwise
            sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, fast ? 0 : 30_000_000);
        }

        /** Something that wakes us at the first step of a walk. */
        private void armTrigger() {
            if (wakeDetector != null) sm.registerListener(this, wakeDetector, SensorManager.SENSOR_DELAY_NORMAL);
            else if (sigMotion != null) sm.requestTriggerSensor(this, sigMotion);
            else if (detector != null && !source.equals("detector")) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000);
            else if (counter != null && !source.equals("counter")) registerCounter(false);
        }

        private void disarmTrigger() {
            if (wakeDetector != null) sm.unregisterListener(this, wakeDetector);
        }

        private void startAccel() {
            Sensor a = wakeAccel != null ? wakeAccel : accel;
            if (a == null) return;
            int fifo = a.getFifoMaxEventCount();
            // deliver in batches of up to 10 s, but before the sensor's buffer would overflow
            int latencyUs = fifo > 0 ? (int) Math.min(10_000_000L, fifo * 40_000L * 8 / 10) : 0;
            pedo.reset();
            net.reset();
            sm.registerListener(this, a, 40_000, latencyUs);          // 25 samples a second
            // where is the phone: proximity reports only on change, so it costs almost nothing
            if (!source.equals("accel") && proximity != null) sm.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            // A normal (non-wake-up) sensor loses its data while the phone sleeps, and with no buffer
            // nothing can be batched: only then keep the CPU awake, and only while walking.
            if (a == accel || fifo == 0) holdWake();
            accelOn = true;
        }

        private void stopAccel() {
            if (wakeAccel != null) sm.unregisterListener(this, wakeAccel);
            if (accel != null) sm.unregisterListener(this, accel);
            if (proximity != null) sm.unregisterListener(this, proximity);
            releaseWake();
            accelOn = false;
        }

        private void holdWake() {
            if (wake == null) {
                wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stride:bout");
                wake.setReferenceCounted(false);
            }
            wake.acquire(5 * 60_000L);      // renewed every second while walking; times out on its own
        }

        private void releaseWake() {
            if (wake != null && wake.isHeld()) wake.release();
        }

        @Override public void onTrigger(TriggerEvent e) {
            if (boost) startBout(e.timestamp);
        }

        @Override public void onSensorChanged(SensorEvent e) {
            long ts = e.timestamp;
            int type = e.sensor.getType();
            if (type == Sensor.TYPE_STEP_COUNTER) {
                long raw = (long) e.values[0];
                long delta;
                if (source.equals("counter")) {
                    int boot = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
                    delta = repo.counterDelta(raw, boot);
                    if (delta > 0) stored("counter", ts, delta);
                } else {
                    delta = lastRaw < 0 || raw < lastRaw ? 0 : raw - lastRaw;
                    lastRaw = raw;
                }
                if (boost) {
                    if (delta > 0 && !fusion.active() && wakeDetector == null && sigMotion == null && detector == null) startBout(ts);
                    fusion.chip(delta, ts);
                    resumeIfWalking();
                }
            } else if (type == Sensor.TYPE_STEP_DETECTOR) {
                if (source.equals("detector") && !e.sensor.isWakeUpSensor()) stored("detector", ts, 1);
                if (boost) {
                    if (detectorForChip && !e.sensor.isWakeUpSensor()) fusion.chip(1, ts);
                    if (!fusion.active() || fusion.stopped()) startBout(ts);
                }
            } else if (type == Sensor.TYPE_ACCELEROMETER) {
                long r0 = pedo.rhythmSteps;
                int peaks = pedo.onSample(e.values[0], e.values[1], e.values[2], ts);
                int model = net.onSample(e.values[0], e.values[1], e.values[2], 0, ts);
                if (source.equals("accel")) {
                    if (peaks > 0) stored("accel", ts, peaks);
                    return;
                }
                place.onAccel(e.values[0], e.values[1], e.values[2], ts);
                // the model counts only while the peak counter agrees it's walking (it never saw cars in training)
                fusion.motion(HAND_MOTION_NET ? (pedo.walking ? model : 0) : peaks, ts);
                fusion.rhythm((int) (pedo.rhythmSteps - r0), ts);
                if (ts - lastTickNs >= 1_000_000_000L) {
                    lastTickNs = ts;
                    fusion.vote(place.current(ts), ts);
                    if (pedo.walking) periodN++;
                    tick(ts);
                }
            } else if (type == Sensor.TYPE_PROXIMITY) {
                place.onProximity(e.values[0] < Math.min(5f, e.sensor.getMaximumRange()), ts);
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }

        // ----- bouts -----

        private void startBout(long ts) {
            boolean resuming = fusion.active();
            fusion.start(ts);
            if (!resuming) {
                fusion.vehicleAt = Vehicle.available(app) ? ns -> Vehicle.at(app, ns) : null;
                place.resetVotes();
                periodN = 0;
                disarmTrigger();
                lastRaw = -1;
                registerCounter(true);
                // no step counter: use step-detector events as the chip side of the bout
                detectorForChip = counter == null && detector != null;
                if (detectorForChip && !source.equals("detector")) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL);
            }
            main.removeCallbacks(graceCheck);
            if (!accelOn) startAccel();
        }

        /** A chip step after we stopped means walking again. */
        private void resumeIfWalking() {
            if (fusion.active() && !fusion.stopped() && !accelOn) {
                main.removeCallbacks(graceCheck);
                startAccel();
            }
        }

        private final Runnable graceCheck = () -> tick(SystemClock.elapsedRealtimeNanos());

        private void tick(long ts) {
            int state = fusion.tick(ts);
            if (state == StepFusion.WALKING) {
                if (wake != null && wake.isHeld()) wake.acquire(5 * 60_000L);
            } else if (state == StepFusion.STOPPED) {
                if (accelOn) stopAccel();
                main.removeCallbacks(graceCheck);
                main.postDelayed(graceCheck, 5_000);
            } else if (state == StepFusion.CLOSED) {
                closeBout();
            }
        }

        private void closeBout() {
            stopAccel();
            main.removeCallbacks(graceCheck);
            long added = 0;
            if (fusion.usedMotion) {
                // place the extra steps in the 15-minute buckets where they happened
                TreeMap<Long, Long> perBucket = new TreeMap<>();
                for (Map.Entry<Long, Long> e : fusion.motionBySecond.entrySet()) {
                    long wall = StepRepository.bucketStart(wallMs(e.getKey() * 1_000_000_000L));
                    Long sum = perBucket.get(wall);
                    perBucket.put(wall, (sum == null ? 0 : sum) + e.getValue());
                }
                ZoneId zone = ZoneId.systemDefault();
                long now = System.currentTimeMillis();
                for (Map.Entry<Long, Long> e : perBucket.entrySet()) {
                    repo.addSteps("boost", e.getKey(), e.getValue(), zone, now);
                    added += e.getValue();
                }
            }
            long removed = 0;
            if (fusion.vehicle) {
                // fake chip steps in a vehicle: take them off the buckets they were counted in
                ZoneId zone = ZoneId.systemDefault();
                long now = System.currentTimeMillis();
                for (Map.Entry<Long, Long> e : fusion.chipBySecond.entrySet()) {
                    repo.addMinus(wallMs(e.getKey() * 1_000_000_000L), e.getValue(), zone, now);
                    removed += e.getValue();
                }
            }
            SharedPreferences p = StepStore.prefs(app);
            float mp = pedo.medianPeak();
            if (fusion.agreed && periodN >= 10 && fusion.place == Placement.HAND && mp >= HAND_PEAK_MIN && mp <= HAND_PEAK_MAX) {
                // steady-hand bout where chip and motion agreed: learn how strongly this person's phone bounces
                float hp = p.getFloat("userHandPeak", 0);
                float v = hp == 0 ? mp : 0.9f * hp + 0.1f * mp;              // slow: one odd bout moves it little
                p.edit().putFloat("userHandPeak", Math.max(HAND_PEAK_MIN, Math.min(HAND_PEAK_MAX, v)))
                        .putInt("userHandBouts", p.getInt("userHandBouts", 0) + 1).apply();
                learned(p);
            }
            String today = LocalDate.now().toString();
            boolean same = today.equals(p.getString("dbgDate", ""));
            p.edit().putString("dbgDate", today)
                    .putLong("dbgChip", (same ? p.getLong("dbgChip", 0) : 0) + fusion.chipSteps)
                    .putLong("dbgMotion", (same ? p.getLong("dbgMotion", 0) : 0) + fusion.motionSteps)
                    .putLong("dbgAdded", (same ? p.getLong("dbgAdded", 0) : 0) + added)
                    .putLong("dbgBouts", (same ? p.getLong("dbgBouts", 0) : 0) + 1)
                    .putLong("dbgVehicle", (same ? p.getLong("dbgVehicle", 0) : 0) + removed)
                    .putString("dbgPlace", Placement.NAMES[fusion.place])
                    .apply();

            if (source.equals("counter")) registerCounter(false);
            else if (counter != null) sm.unregisterListener(this, counter);
            if (detectorForChip && !source.equals("detector")) sm.unregisterListener(this, detector);
            detectorForChip = false;
            armTrigger();

            StepRecordingManager.sync(app, () -> { });     // a bout just ended: good moment to sync
            Widgets.updateAll(app);
            StepService.refresh(app, true);
        }

        /** Phone-sensor sources store their steps as they come. */
        private void stored(String src, long ts, long steps) {
            repo.addSteps(src, wallMs(ts), steps, ZoneId.systemDefault(), System.currentTimeMillis());
            StepService.refresh(app, false);
            long now = SystemClock.elapsedRealtime();
            if (now - lastWidget > 5 * 60_000L) {        // widgets: at most every 5 minutes from the background
                lastWidget = now;
                Widgets.updateAll(app);
            }
        }
    }

    // =====================================================================================
    /** While the app is visible: instant counts for display, pushed at most once a second. */
    private static final class Live implements SensorEventListener {
        private final Context app;
        private final SensorManager sm;
        private final Listener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Sensor counter, detector, accel;
        private String date;
        private long base, firstRaw = -1, counterSteps, detectorSteps, shown = -1, lastPush, lastWidget;
        private boolean pushPending;
        private MotionCounter cal;
        private int calSteps;

        Live(Context c, Listener l) {
            app = c.getApplicationContext();
            sm = app.getSystemService(SensorManager.class);
            listener = l;
            counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            date = LocalDate.now().toString();
            base = StepRepository.get(app).day(date);
            lastWidget = SystemClock.uptimeMillis();
            if (StepService.hasPermission(app)) {
                if (counter != null) sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, 0);
                if (detector != null) sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, 0);
            }
        }

        long counterSteps() { return counter == null ? -1 : counterSteps; }

        long today() {
            String d = LocalDate.now().toString();
            if (!d.equals(date)) {                       // midnight while the app is open
                date = d;
                base = StepRepository.get(app).day(d);
                firstRaw = -1;
                counterSteps = 0;
                detectorSteps = 0;
            }
            long liveSteps = Math.max(counterSteps, detectorSteps);
            return Math.max(StepRepository.get(app).day(d), base + liveSteps);
        }

        void stop() {
            sm.unregisterListener(this);
            main.removeCallbacksAndMessages(null);
            // remember what we showed, so widgets don't drop back until the next sync catches up
            long keep = Math.max(StepRepository.get(app).day(date), base + (counter != null ? counterSteps : detectorSteps));
            StepStore.prefs(app).edit().putString("liveDate", date).putLong("liveSteps", keep).apply();
        }

        void startCalibration() {
            if (accel == null) return;
            cal = new MotionCounter();
            cal.floor = 0.08f;          // listen for weaker steps than usual; the walk tells us where to set it
            calSteps = 0;
            sm.registerListener(this, accel, 40_000);
        }

        float[] stopCalibration() {
            if (accel != null) sm.unregisterListener(this, accel);
            float[] r = {calSteps, cal == null ? 0 : cal.medianPeak()};
            cal = null;
            return r;
        }

        @Override public void onSensorChanged(SensorEvent e) {
            int type = e.sensor.getType();
            if (type == Sensor.TYPE_STEP_COUNTER) {
                long raw = (long) e.values[0];
                if (firstRaw < 0 || raw < firstRaw) firstRaw = raw - counterSteps;
                counterSteps = raw - firstRaw;
            } else if (type == Sensor.TYPE_STEP_DETECTOR) {
                detectorSteps++;
            } else if (type == Sensor.TYPE_ACCELEROMETER && cal != null) {
                calSteps += cal.onSample(e.values[0], e.values[1], e.values[2], e.timestamp);
                return;
            }
            if (pushPending) return;
            pushPending = true;
            long wait = Math.max(0, 1000 - (SystemClock.uptimeMillis() - lastPush));
            main.postDelayed(push, wait);
        }

        private final Runnable push = this::push;

        private void push() {
            pushPending = false;
            lastPush = SystemClock.uptimeMillis();
            long t = today();
            if (t != shown) {
                shown = t;
                listener.onSteps(t);
            }
            if (lastPush - lastWidget > 60_000L) {        // widgets: at most once a minute while live
                lastWidget = lastPush;
                Widgets.updateAll(app);
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }
    }
}
