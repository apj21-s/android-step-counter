package com.arco.stride;

import android.content.ContentValues;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.MediaStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lab mode (hidden): record raw sensors during a walk you count by hand, for tuning on real walks.
 * Saves Download/Stride lab/<time>_<place>.csv: "# truth=N place=P ... wall0=ms ns0=ns" then rows "t_ns,type,v0,v1,v2"
 * (A accel 50 Hz, G gyro 50 Hz, C step counter, D step detector, P proximity, L light,
 * V Activity Recognition vehicle transition: v0 = 1 entered / 0 left, from the 2 h before the end).
 * tools/real/lab2csv.py turns these into the format tools/real/run.sh scores.
 */
final class LabRecorder implements SensorEventListener {
    private static LabRecorder current;

    private final Context app;
    private final SensorManager sm;
    private final String place;
    private final StringBuilder rows = new StringBuilder(1 << 20);
    private final HandlerThread thread = new HandlerThread("stride-lab");
    private final PowerManager.WakeLock wake;
    private final long startMs = System.currentTimeMillis();
    private final long startNs = android.os.SystemClock.elapsedRealtimeNanos();   // same clock as sensor timestamps
    private long counterFirst = -1, counterLast = -1, detector;
    // live replay at 25 Hz (every second accel sample), the way the app runs
    private final MotionCounter peaks = new MotionCounter();
    private final StepNet net = StepNet.standard();
    private long peakSteps, netSteps, walkingNetSteps;
    private int accelN;

    private LabRecorder(Context c, String place) {
        app = c.getApplicationContext();
        sm = app.getSystemService(SensorManager.class);
        this.place = place.replaceAll("[^a-z_]", "");
        wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stride:lab");
    }

    static synchronized void start(Context c, String place) {
        if (current != null) return;
        current = new LabRecorder(c, place);
        current.begin();
    }

    static synchronized boolean running() { return current != null; }

    /** Stop and save. Returns JSON {truth, chip, detector, peaks, model, file} or {error}. */
    static synchronized String stop(int truth) {
        if (current == null) return "{\"error\":\"not recording\"}";
        String r = current.end(truth);
        current = null;
        return r;
    }

    private void begin() {
        wake.acquire(60 * 60_000L);           // screen-off walks (pocket) must keep recording; max 1 hour
        thread.start();
        Handler h = new Handler(thread.getLooper());
        reg(Sensor.TYPE_ACCELEROMETER, 20_000, h);
        reg(Sensor.TYPE_GYROSCOPE, 20_000, h);
        reg(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL, h);
        reg(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL, h);
        reg(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL, h);
        reg(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL, h);
    }

    private void reg(int type, int us, Handler h) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, us, 0, h);
    }

    private String end(int truth) {
        sm.unregisterListener(this);
        // vehicle transitions near the recording (they arrive late, so take them at the end)
        long now = android.os.SystemClock.elapsedRealtimeNanos();
        for (String ev : StepStore.prefs(app).getString("arEvents", "").split(",")) {
            int k = ev.indexOf(':');
            if (k < 0) continue;
            long t = Long.parseLong(ev.substring(k + 1));
            if (now - t < 2 * 3_600_000_000_000L) synchronized (rows) { rows.append(t).append(",V,").append(ev.charAt(0)).append(",0,0\n"); }
        }
        thread.quitSafely();
        if (wake.isHeld()) wake.release();
        JSONObject o = new JSONObject();
        try {
            long chip;
            synchronized (rows) { chip = counterFirst < 0 ? 0 : counterLast - counterFirst; }
            o.put("truth", truth).put("place", place).put("vehicleOn", Vehicle.available(app)).put("chip", chip).put("detector", detector)
                    .put("peaks", peakSteps).put("model", netSteps).put("modelWalking", walkingNetSteps);
            String name = String.format(Locale.ROOT, "%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS_%2$s.csv", startMs, place);
            o.put("file", save(name, String.format(Locale.ROOT, "# truth=%d place=%s chip=%d detector=%d peaks=%d model=%d wall0=%d ns0=%d%n",
                    truth, place, chip, detector, peakSteps, netSteps, startMs, startNs)));
        } catch (JSONException | RuntimeException e) {
            try { o.put("error", String.valueOf(e.getMessage())); } catch (JSONException ignored) { /* can't happen */ }
        }
        return o.toString();
    }

    private String save(String name, String header) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Stride lab");
        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("could not create file");
        try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("could not open file");
            out.write(header.getBytes(StandardCharsets.UTF_8));
            synchronized (rows) { out.write(rows.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
        return "Download/Stride lab/" + name;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        char tag;
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: tag = 'A'; break;
            case Sensor.TYPE_GYROSCOPE: tag = 'G'; break;
            case Sensor.TYPE_STEP_COUNTER: tag = 'C'; break;
            case Sensor.TYPE_STEP_DETECTOR: tag = 'D'; break;
            case Sensor.TYPE_PROXIMITY: tag = 'P'; break;
            case Sensor.TYPE_LIGHT: tag = 'L'; break;
            default: return;
        }
        float[] x = e.values;
        synchronized (rows) {
            rows.append(e.timestamp).append(',').append(tag);
            for (int i = 0; i < 3; i++) rows.append(',').append(i < x.length ? x[i] : 0);
            rows.append('\n');
            if (type == Sensor.TYPE_STEP_COUNTER) {
                long raw = (long) x[0];
                if (counterFirst < 0) counterFirst = raw;
                counterLast = raw;
            }
        }
        if (type == Sensor.TYPE_STEP_DETECTOR) detector++;
        if (type == Sensor.TYPE_ACCELEROMETER && (accelN++ & 1) == 0) {
            peakSteps += peaks.onSample(x[0], x[1], x[2], e.timestamp);
            int n = net.onSample(x[0], x[1], x[2], 0, e.timestamp);
            netSteps += n;
            if (peaks.walking) walkingNetSteps += n;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
