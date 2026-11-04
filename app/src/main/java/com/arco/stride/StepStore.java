package com.arco.stride;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Iterator;

/**
 * Today's steps and history, fed by the phone's hardware step counter.
 *
 * The sensor reports a running total since the phone booted. We keep the last raw value
 * and add the difference on each reading, so a reboot (raw value drops) and midnight
 * (day changes) are both handled without a fixed baseline.
 */
public final class StepStore {
    private static final String P = "stride";
    private static final int KEEP_DAYS = 400;

    private StepStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static String today() { return LocalDate.now().toString(); }

    /** Feed one raw sensor value. Returns how many steps were added. */
    static synchronized long onRaw(Context c, long raw) {
        SharedPreferences p = prefs(c);
        rollIfNeeded(c);
        long last = p.getLong("lastRaw", -1);
        SharedPreferences.Editor e = p.edit().putLong("lastRaw", raw);
        if (!p.contains("firstDay")) e.putString("firstDay", today());
        if (last < 0) { e.apply(); return 0; }          // first reading: nothing to add yet
        long delta = raw - last;
        if (delta < 0) delta = raw;                       // phone rebooted: sensor restarted at 0
        if (delta == 0) { e.apply(); return 0; }
        e.putLong("steps", p.getLong("steps", 0) + delta)
         .putLong("total", p.getLong("total", 0) + delta)
         .apply();
        return delta;
    }

    /** Add (or take back, if negative) steps found by the motion detector. */
    static synchronized void addSteps(Context c, long n) {
        if (n == 0) return;
        rollIfNeeded(c);
        SharedPreferences p = prefs(c);
        p.edit().putLong("steps", Math.max(0, p.getLong("steps", 0) + n))
                .putLong("total", Math.max(0, p.getLong("total", 0) + n)).apply();
    }

    /** Close out yesterday when the date changes. Returns true if it rolled. */
    static synchronized boolean rollIfNeeded(Context c) {
        SharedPreferences p = prefs(c);
        String day = p.getString("day", null), now = today();
        if (now.equals(day)) return false;
        SharedPreferences.Editor e = p.edit().putString("day", now).putLong("steps", 0);
        if (day != null) {
            try {
                JSONObject h = history(p);
                h.put(day, p.getLong("steps", 0));
                String cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString();
                for (Iterator<String> it = h.keys(); it.hasNext(); ) if (it.next().compareTo(cutoff) < 0) it.remove();
                e.putString("history", h.toString());
            } catch (JSONException ignored) { }
        }
        e.apply();
        return true;
    }

    static JSONObject history(SharedPreferences p) {
        try { return new JSONObject(p.getString("history", "{}")); }
        catch (JSONException ex) { return new JSONObject(); }
    }

    static long steps(Context c) { rollIfNeeded(c); return prefs(c).getLong("steps", 0); }

    // ---- Settings ----
    static int goal(Context c) { return prefs(c).getInt("goal", 8000); }
    static int height(Context c) { return prefs(c).getInt("height", 175); }
    static boolean live(Context c) { return prefs(c).getBoolean("live", true); }
    static boolean boost(Context c) { return prefs(c).getBoolean("boost", true); }
    static String style(Context c) { return prefs(c).getString("wstyle", "obsidian"); }
    static int a1(Context c) { return prefs(c).getInt("a1", 0xFFDC143C); }
    static int a2(Context c) { return prefs(c).getInt("a2", 0xFFFF2D55); }

    static double km(Context c, long s) { return s * height(c) * 0.415 / 100 / 1000; }
    static long kcal(long s) { return Math.round(s * 0.04); }

    /** Steps for the last 7 days, oldest first, today last. */
    static long[] week(Context c) {
        JSONObject h = history(prefs(c));
        long[] w = new long[7];
        for (int i = 0; i < 6; i++) w[i] = h.optLong(LocalDate.now().minusDays(6 - i).toString(), 0);
        w[6] = StepEngine.display(c);
        return w;
    }

    /** Everything the app screen needs, as JSON. */
    static String stateJson(Context c, boolean hasSensor, boolean permission, String debug) {
        rollIfNeeded(c);
        SharedPreferences p = prefs(c);
        try {
            JSONObject o = new JSONObject();
            o.put("steps", StepEngine.display(c));
            o.put("total", StepEngine.displayTotal(c));
            o.put("debug", debug);
            o.put("today", today());
            o.put("firstDay", p.getString("firstDay", today()));
            o.put("goal", goal(c));
            o.put("height", height(c));
            o.put("live", live(c));
            o.put("amoled", p.getBoolean("amoled", false));
            o.put("haptics", p.getBoolean("haptics", true));
            o.put("boost", boost(c));
            o.put("wstyle", style(c));
            o.put("accent", p.getString("accent", "#dc143c"));
            o.put("hasSensor", hasSensor);
            o.put("permission", permission);
            JSONObject h = history(p);
            JSONArray arr = new JSONArray();
            for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                String k = it.next();
                arr.put(new JSONArray().put(k).put(h.getLong(k)));
            }
            o.put("history", arr);
            return o.toString();
        } catch (JSONException ex) {
            return "{}";
        }
    }
}
