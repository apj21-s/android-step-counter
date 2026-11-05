package com.arco.stride;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only place step totals live. Everything (Today, History, widgets, notification) reads from here.
 *
 * step_buckets: steps per 15-minute bucket per source. Every time zone is a whole number of
 *   15 minutes from UTC, so these buckets always line up with local hours and midnight, also
 *   across DST and travel. A bucket's value is the LARGEST of its sources, never their sum.
 *   Sources: recording (Google Recording API), counter / detector / accel (phone sensors,
 *   when Play services is missing), boost (extra steps from Max accuracy bouts).
 * daily_steps: the day totals worked out from step_buckets (plus migrated history).
 * legacy_days: history from older versions (day totals only).
 * counter_state: last raw step-counter value, to turn a since-boot total into deltas.
 * hc_written: what we already sent to Health Connect.
 * bucket_minus: chip steps counted while in a vehicle (Activity Recognition), taken off the
 *   bucket's value (never below zero).
 */
public final class StepRepository extends SQLiteOpenHelper {
    static final long BUCKET_MS = 15 * 60_000L;

    private static StepRepository instance;

    static synchronized StepRepository get(Context c) {
        if (instance == null) instance = new StepRepository(c.getApplicationContext(), "stride.db");
        return instance;
    }

    /** name == null gives an in-memory database (tests). */
    StepRepository(Context c, String name) {
        super(c, name, null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE step_buckets (start_ms INTEGER NOT NULL, source TEXT NOT NULL, date TEXT NOT NULL,"
                + " steps INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (start_ms, source))");
        db.execSQL("CREATE INDEX step_buckets_date ON step_buckets (date)");
        db.execSQL("CREATE TABLE daily_steps (date TEXT PRIMARY KEY, steps INTEGER NOT NULL DEFAULT 0,"
                + " source TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE legacy_days (date TEXT PRIMARY KEY, steps INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE counter_state (id INTEGER PRIMARY KEY, last_raw INTEGER NOT NULL, boot_count INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE hc_written (start_ms INTEGER PRIMARY KEY, steps INTEGER NOT NULL)");
        createMinus(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMinus(db);
    }

    private static void createMinus(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bucket_minus (start_ms INTEGER PRIMARY KEY, date TEXT NOT NULL, steps INTEGER NOT NULL)");
    }

    /** Take `steps` off the bucket holding `atMs` (fake steps counted in a vehicle). */
    synchronized void addMinus(long atMs, long steps, ZoneId zone, long now) {
        if (steps <= 0) return;
        long start = bucketStart(atMs);
        String date = dateOf(start, zone);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO bucket_minus (start_ms, date, steps) VALUES (?, ?, 0)", new Object[]{start, date});
            db.execSQL("UPDATE bucket_minus SET steps = steps + ? WHERE start_ms = ?", new Object[]{steps, start});
            recomputeDay(db, date, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static long bucketStart(long ms) { return ms - Math.floorMod(ms, BUCKET_MS); }

    static String dateOf(long ms, ZoneId zone) { return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString(); }

    static long midnight(LocalDate day, ZoneId zone) { return day.atStartOfDay(zone).toInstant().toEpochMilli(); }

    // ---------- writing ----------

    /**
     * A sync result: these are ALL of `source`'s buckets between from and to.
     * Old rows of that source in the range are replaced, never added to, so syncing
     * the same data twice changes nothing.
     */
    synchronized void replaceRange(String source, long fromMs, long toMs, long[] starts, long[] steps, ZoneId zone, long now) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> dates = new HashSet<>();
        db.beginTransaction();
        try {
            String[] args = {source, String.valueOf(fromMs), String.valueOf(toMs)};
            Cursor c = db.rawQuery("SELECT DISTINCT date FROM step_buckets WHERE source = ? AND start_ms >= ? AND start_ms < ?", args);
            while (c.moveToNext()) dates.add(c.getString(0));
            c.close();
            db.delete("step_buckets", "source = ? AND start_ms >= ? AND start_ms < ?", args);
            for (int i = 0; i < starts.length; i++) {
                if (steps[i] <= 0) continue;
                long start = bucketStart(starts[i]);
                String date = dateOf(start, zone);
                ContentValues v = new ContentValues();
                v.put("start_ms", start);
                v.put("source", source);
                v.put("date", date);
                v.put("steps", steps[i]);
                v.put("updated_at", now);
                db.insertWithOnConflict("step_buckets", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                dates.add(date);
            }
            for (String d : dates) recomputeDay(db, d, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Live sources (phone sensors, bouts) add steps as they happen. */
    synchronized void addSteps(String source, long atMs, long steps, ZoneId zone, long now) {
        if (steps <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        long start = bucketStart(atMs);
        String date = dateOf(start, zone);
        db.beginTransaction();
        try {
            db.execSQL("UPDATE step_buckets SET steps = steps + ?, updated_at = ? WHERE start_ms = ? AND source = ?",
                    new Object[]{steps, now, start, source});
            Cursor c = db.rawQuery("SELECT changes()", null);
            c.moveToFirst();
            boolean updated = c.getLong(0) > 0;
            c.close();
            if (!updated) {
                ContentValues v = new ContentValues();
                v.put("start_ms", start);
                v.put("source", source);
                v.put("date", date);
                v.put("steps", steps);
                v.put("updated_at", now);
                db.insert("step_buckets", null, v);
            }
            recomputeDay(db, date, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Turn a raw step-counter value (steps since the phone booted) into new steps.
     * A reboot shows up as a new boot count, or as the value going down: then the
     * new value itself is the steps since the reboot.
     */
    synchronized long counterDelta(long raw, int bootCount) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT last_raw, boot_count FROM counter_state WHERE id = 1", null);
        long delta = 0;
        if (c.moveToFirst()) {
            long last = c.getLong(0);
            int lastBoot = c.getInt(1);
            boolean rebooted = (bootCount >= 0 && lastBoot >= 0 && bootCount != lastBoot) || raw < last;
            delta = rebooted ? raw : raw - last;
        }
        c.close();
        ContentValues v = new ContentValues();
        v.put("id", 1);
        v.put("last_raw", raw);
        v.put("boot_count", bootCount);
        db.insertWithOnConflict("counter_state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return delta;
    }

    /** Day total = sum over buckets of the largest source in each bucket (or migrated history if larger). */
    private void recomputeDay(SQLiteDatabase db, String date, long now) {
        Map<Long, Long> minus = new HashMap<>();
        Cursor mc = db.rawQuery("SELECT start_ms, steps FROM bucket_minus WHERE date = ?", new String[]{date});
        while (mc.moveToNext()) minus.put(mc.getLong(0), mc.getLong(1));
        mc.close();
        Cursor c = db.rawQuery("SELECT start_ms, source, steps FROM step_buckets WHERE date = ? ORDER BY start_ms, steps DESC, source", new String[]{date});
        long total = 0, lastStart = Long.MIN_VALUE;
        Map<String, Long> bySource = new HashMap<>();
        while (c.moveToNext()) {
            long start = c.getLong(0);
            if (start == lastStart) continue;           // a smaller source in the same bucket
            lastStart = start;
            String source = c.getString(1);
            Long m = minus.get(start);
            long steps = Math.max(0, c.getLong(2) - (m == null ? 0 : m));
            total += steps;
            Long sum = bySource.get(source);
            bySource.put(source, (sum == null ? 0 : sum) + steps);
        }
        c.close();
        String source = "none";
        long best = -1;
        for (Map.Entry<String, Long> e : bySource.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); source = e.getKey(); }
        }
        Cursor l = db.rawQuery("SELECT steps FROM legacy_days WHERE date = ?", new String[]{date});
        if (l.moveToFirst() && l.getLong(0) > total) {
            total = l.getLong(0);
            source = "legacy";
        }
        l.close();
        ContentValues v = new ContentValues();
        v.put("date", date);
        v.put("steps", total);
        v.put("source", source);
        v.put("updated_at", now);
        db.insertWithOnConflict("daily_steps", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Copy history from the old SharedPreferences format, once. */
    synchronized void migrateLegacy(SharedPreferences p) {
        if (p.getBoolean("migratedV2", false)) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            Map<String, Long> days = new LinkedHashMap<>();
            try {
                JSONObject h = new JSONObject(p.getString("history", "{}"));
                for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                    String k = it.next();
                    days.put(k, h.getLong(k));
                }
            } catch (JSONException ignored) {
                // unreadable old history: nothing to migrate
            }
            String day = p.getString("day", null);
            if (day != null) days.put(day, p.getLong("steps", 0));
            for (Map.Entry<String, Long> e : days.entrySet()) {
                if (e.getValue() <= 0) continue;
                ContentValues v = new ContentValues();
                v.put("date", e.getKey());
                v.put("steps", e.getValue());
                db.insertWithOnConflict("legacy_days", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                recomputeDay(db, e.getKey(), now);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        p.edit().putBoolean("migratedV2", true).apply();
    }

    // ---------- reading ----------

    synchronized long day(String date) {
        Cursor c = getReadableDatabase().rawQuery("SELECT steps FROM daily_steps WHERE date = ?", new String[]{date});
        long s = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return s;
    }

    synchronized String daySource(String date) {
        Cursor c = getReadableDatabase().rawQuery("SELECT source FROM daily_steps WHERE date = ?", new String[]{date});
        String s = c.moveToFirst() ? c.getString(0) : "none";
        c.close();
        return s;
    }

    /** date -> steps for every stored day from `fromDate` on, oldest first. */
    synchronized Map<String, Long> daysFrom(String fromDate) {
        Map<String, Long> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT date, steps FROM daily_steps WHERE date >= ? ORDER BY date", new String[]{fromDate});
        while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        c.close();
        return out;
    }

    /** Steps in each local hour of a day (23, 24 or 25 hours on DST days). */
    synchronized long[] hours(LocalDate day, ZoneId zone) {
        long start = midnight(day, zone), end = midnight(day.plusDays(1), zone);
        int n = (int) ((end - start) / 3_600_000L);
        long[] out = new long[n];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT b.start_ms, MAX(MAX(b.steps) - IFNULL(m.steps, 0), 0) FROM step_buckets b LEFT JOIN bucket_minus m ON m.start_ms = b.start_ms"
                        + " WHERE b.start_ms >= ? AND b.start_ms < ? GROUP BY b.start_ms",
                new String[]{String.valueOf(start), String.valueOf(end)});
        while (c.moveToNext()) {
            int h = (int) ((c.getLong(0) - start) / 3_600_000L);
            if (h >= 0 && h < n) out[h] += c.getLong(1);
        }
        c.close();
        return out;
    }

    /** Steps per source today, for the debug line. */
    synchronized Map<String, Long> sources(String date) {
        Map<String, Long> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT source, SUM(steps) FROM step_buckets WHERE date = ? GROUP BY source", new String[]{date});
        while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        c.close();
        return out;
    }

    /** Buckets whose value (largest source, less vehicle steps) changed since we last sent it to Health Connect: {start, steps}. */
    synchronized List<long[]> changedForHealth(long fromMs) {
        List<long[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT b.start_ms, MAX(MAX(b.steps) - IFNULL(m.steps, 0), 0), h.steps FROM step_buckets b"
                        + " LEFT JOIN hc_written h ON h.start_ms = b.start_ms LEFT JOIN bucket_minus m ON m.start_ms = b.start_ms"
                        + " WHERE b.start_ms >= ? GROUP BY b.start_ms", new String[]{String.valueOf(fromMs)});
        while (c.moveToNext()) {
            long steps = c.getLong(1);
            if (c.isNull(2) || c.getLong(2) != steps) out.add(new long[]{c.getLong(0), steps});
        }
        c.close();
        return out;
    }

    synchronized void markWritten(List<long[]> buckets) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long[] b : buckets) {
                ContentValues v = new ContentValues();
                v.put("start_ms", b[0]);
                v.put("steps", b[1]);
                db.insertWithOnConflict("hc_written", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
