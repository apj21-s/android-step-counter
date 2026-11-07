package com.arco.stride;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Recording a walk you count by hand. Used for calibration and for Lab recordings (same thing).
 *
 * Works with the screen off and the app closed: while recording, the step service is kept in the
 * foreground (Android stops accelerometer data to background apps) and a wake lock is held.
 * Rows go straight to a file in app storage, so a crash or kill loses nothing; Stop copies it to
 * Download/Stride lab/<time>_<place>.csv with a header line:
 *   # truth=N place=P chip=.. detector=.. peaks=.. hand=.. model=.. wall0=ms ns0=ns
 * Rows: t_ns,type,v0,v1,v2  (A accel 50 Hz, G gyro 50 Hz, C step counter, D step detector,
 *   P proximity, L light, V vehicle transition 1 entered / 0 left).
 */
final class LabRecorder implements SensorEventListener {
    private static LabRecorder current;

    private final Context app;
    private final SensorManager sm;
    private final String place;
    private final HandlerThread thread = new HandlerThread("stride-rec");
    private final PowerManager.WakeLock wake;
    private final long startMs = System.currentTimeMillis(), startNs = SystemClock.elapsedRealtimeNanos();
    private final File file;
    private BufferedWriter out;
    private long counterFirst = -1, counterLast = -1, detector, rows, lastFlushNs;
    // replayed live at 25 Hz, the way the app runs
    private final MotionCounter mc = new MotionCounter();
    private final StepNet net = StepNet.standard();
    private long peaks, rhythm, model;
    private long lastAccelNs;

    private LabRecorder(Context c, String place) throws IOException {
        app = c.getApplicationContext();
        sm = app.getSystemService(SensorManager.class);
        this.place = place.replaceAll("[^a-z_]", "");
        wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stride:rec");
        file = new File(dir(app), "recording.csv");
        out = new BufferedWriter(new FileWriter(file, false), 1 << 16);
    }

    private static File dir(Context app) {
        File d = new File(app.getFilesDir(), "rec");
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("no storage");
        return d;
    }

    /** Start recording. Returns null, or an error message. */
    static synchronized String start(Context c, String place) {
        if (current != null) return null;
        try {
            current = new LabRecorder(c, place);
        } catch (IOException | RuntimeException e) {
            return "Can't write to storage: " + e.getMessage();
        }
        StepStore.prefs(c).edit().putBoolean("recActive", true).putString("recPlace", current.place).apply();
        StepService.start(c);                 // keeps the app in the foreground while the screen is off
        current.begin();
        return null;
    }

    static synchronized boolean running() { return current != null; }

    /** Live status for the screen: {"on", "seconds", "rows", "chip", "peaks"}. */
    static synchronized String status() {
        JSONObject o = new JSONObject();
        try {
            if (current == null) return o.put("on", false).toString();
            LabRecorder r = current;
            synchronized (r) {
                o.put("on", true).put("place", r.place).put("seconds", (System.currentTimeMillis() - r.startMs) / 1000)
                        .put("rows", r.rows).put("chip", r.counterFirst < 0 ? 0 : r.counterLast - r.counterFirst).put("peaks", r.peaks);
            }
        } catch (JSONException ignored) { /* can't happen */ }
        return o.toString();
    }

    /** Stop, save to Downloads, and return JSON {truth, place, chip, detector, peaks, hand, model, rows, file} or {error}. */
    static synchronized String stop(Context c, int truth) {
        if (current == null) return "{\"error\":\"not recording\"}";
        String r = current.end(truth);
        current = null;
        StepStore.prefs(c).edit().putBoolean("recActive", false).apply();
        StepService.start(c);                 // stops the service again if nothing else needs it
        return r;
    }

    /** Throw away the recording in progress. */
    static synchronized void cancel(Context c) {
        if (current == null) return;
        current.release();
        if (!current.file.delete()) current.file.deleteOnExit();
        current = null;
        StepStore.prefs(c).edit().putBoolean("recActive", false).apply();
        StepService.start(c);
    }

    private void begin() {
        wake.acquire(2 * 60 * 60_000L);       // at most 2 hours
        thread.start();
        Handler h = new Handler(thread.getLooper());
        for (int type : new int[]{Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE}) reg(type, 20_000, h);
        for (int type : new int[]{Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT})
            reg(type, SensorManager.SENSOR_DELAY_NORMAL, h);
    }

    private void reg(int type, int us, Handler h) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, us, 0, h);
    }

    private void release() {
        sm.unregisterListener(this);
        thread.quitSafely();
        if (wake.isHeld()) wake.release();
        synchronized (this) {
            try { out.close(); } catch (IOException ignored) { /* already closed */ }
        }
    }

    private String end(int truth) {
        release();
        JSONObject o = new JSONObject();
        try {
            // vehicle transitions near the recording (they arrive late, so they're taken at the end)
            StringBuilder v = new StringBuilder();
            long now = SystemClock.elapsedRealtimeNanos();
            for (String ev : StepStore.prefs(app).getString("arEvents", "").split(",")) {
                int k = ev.indexOf(':');
                if (k < 0) continue;
                long t = Long.parseLong(ev.substring(k + 1));
                if (now - t < 2 * 3_600_000_000_000L) v.append(t).append(",V,").append(ev.charAt(0)).append(",0,0\n");
            }
            long chip = counterFirst < 0 ? 0 : counterLast - counterFirst;
            long hand = StepFusion.handEstimate(peaks, rhythm);
            String header = String.format(Locale.ROOT, "# truth=%d place=%s chip=%d detector=%d peaks=%d hand=%d model=%d wall0=%d ns0=%d%n",
                    truth, place, chip, detector, peaks, hand, model, startMs, startNs);
            String name = String.format(Locale.ROOT, "%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS_%2$s.csv", startMs, place);
            String saved = export(app, name, header, file, v.toString());
            if (!file.delete()) file.deleteOnExit();
            o.put("truth", truth).put("place", place).put("chip", chip).put("detector", detector).put("peaks", peaks)
                    .put("hand", hand).put("model", model).put("rows", rows).put("medianPeak", mc.medianPeak())
                    .put("seconds", (System.currentTimeMillis() - startMs) / 1000).put("file", saved);
        } catch (JSONException | RuntimeException e) {
            try { o.put("error", String.valueOf(e.getMessage())); } catch (JSONException ignored) { /* can't happen */ }
        }
        return o.toString();
    }

    /** Copy header + rows file + extra rows into Download/Stride lab/<name>. Returns the shown path. */
    private static String export(Context app, String name, String header, File rowsFile, String extra) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Stride lab");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("could not create the file in Downloads");
        try (OutputStream o = app.getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(rowsFile)) {
            if (o == null) throw new IllegalStateException("could not open the file in Downloads");
            o.write(header.getBytes(StandardCharsets.UTF_8));
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            o.write(extra.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            app.getContentResolver().delete(uri, null, null);
            throw new IllegalStateException("could not save: " + e.getMessage());
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        app.getContentResolver().update(uri, done, null, null);
        return "Download/Stride lab/" + name;
    }

    /** A recording cut off by a crash or kill: save what there is (truth unknown), so nothing is lost. */
    static void recoverLeftover(Context c) {
        Context app = c.getApplicationContext();
        if (running()) return;
        File f = new File(new File(app.getFilesDir(), "rec"), "recording.csv");
        if (!f.exists() || f.length() == 0) return;
        String place = StepStore.prefs(app).getString("recPlace", "unknown");
        try {
            export(app, String.format(Locale.ROOT, "%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS_%2$s_unfinished.csv", f.lastModified(), place),
                    "# truth=unknown place=" + place + " unfinished=1\n", f, "");
        } catch (RuntimeException ignored) {
            // keep the file for the next try
            return;
        }
        if (!f.delete()) f.deleteOnExit();
        StepStore.prefs(app).edit().putBoolean("recActive", false).apply();
    }

    /** Saved recordings, newest first: [{name, kb}]. */
    static String list(Context c) {
        JSONArray a = new JSONArray();
        String[] cols = {MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE};
        try (Cursor cur = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ?", new String[]{"Download/Stride lab%"},
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                a.put(new JSONObject().put("name", cur.getString(0)).put("kb", cur.getLong(1) / 1024));
            }
        } catch (JSONException | RuntimeException e) {
            return "[]";
        }
        return a.toString();
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
        synchronized (this) {
            try {
                out.write(Long.toString(e.timestamp));
                out.write(',');
                out.write(tag);
                for (int i = 0; i < 3; i++) { out.write(','); out.write(Float.toString(i < x.length ? x[i] : 0)); }
                out.write('\n');
                if (e.timestamp - lastFlushNs > 2_000_000_000L) { out.flush(); lastFlushNs = e.timestamp; }   // at most 2 s lost on a kill
            } catch (IOException ignored) {
                // storage full: keep counting, the file just ends early
            }
            rows++;
            if (type == Sensor.TYPE_STEP_COUNTER) {
                long raw = (long) x[0];
                if (counterFirst < 0) counterFirst = raw;
                counterLast = raw;
            }
            if (type == Sensor.TYPE_STEP_DETECTOR) detector++;
            if (type == Sensor.TYPE_ACCELEROMETER && (e.timestamp - lastAccelNs >= 38_000_000L || e.timestamp < lastAccelNs)) {
                lastAccelNs = e.timestamp;                // 25 a second, like the app
                long rb = mc.rhythmSteps;
                peaks += mc.onSample(x[0], x[1], x[2], e.timestamp);
                rhythm += mc.rhythmSteps - rb;
                int n = net.onSample(x[0], x[1], x[2], 0, e.timestamp);
                if (mc.walking) model += n;
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
