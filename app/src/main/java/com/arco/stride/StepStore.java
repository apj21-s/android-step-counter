package com.arco.stride;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Settings (SharedPreferences) and the state the app screen shows. Step data lives in StepRepository. */
public final class StepStore {
    private StepStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("stride", Context.MODE_PRIVATE);
    }

    static int goal(Context c) { return prefs(c).getInt("goal", 8000); }
    static int height(Context c) { return prefs(c).getInt("height", 175); }
    static boolean boost(Context c) { return prefs(c).getBoolean("boost", true); }
    static String style(Context c) { return prefs(c).getString("wstyle", "obsidian"); }
    static int a1(Context c) { return prefs(c).getInt("a1", 0xFFDC143C); }
    static int a2(Context c) { return prefs(c).getInt("a2", 0xFFFF2D55); }

    static double km(Context c, long s) { return s * height(c) * 0.415 / 100 / 1000; }
    static long kcal(long s) { return Math.round(s * 0.04); }

    /** Steps for the last 7 days, oldest first, today last. */
    static long[] week(Context c) {
        LocalDate today = LocalDate.now();
        Map<String, Long> days = StepRepository.get(c).daysFrom(today.minusDays(6).toString());
        long[] w = new long[7];
        for (int i = 0; i < 6; i++) {
            Long v = days.get(today.minusDays(6 - i).toString());
            w[i] = v == null ? 0 : v;
        }
        w[6] = StepEngine.today(c);
        return w;
    }

    /** Everything the app screen needs, as JSON. */
    static String stateJson(Context c) {
        SharedPreferences p = prefs(c);
        StepRepository repo = StepRepository.get(c);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        try {
            JSONObject o = new JSONObject();
            o.put("steps", StepEngine.today(c));
            o.put("today", today.toString());
            o.put("goal", goal(c));
            o.put("height", height(c));
            o.put("amoled", p.getBoolean("amoled", false));
            o.put("haptics", p.getBoolean("haptics", true));
            o.put("boost", boost(c));
            o.put("wstyle", style(c));
            o.put("accent", p.getString("accent", "#dc143c"));
            String source = StepEngine.source(c);
            o.put("source", source);
            o.put("hasSensor", !source.equals("none"));
            o.put("permission", StepService.hasPermission(c));
            o.put("playOk", StepRecordingManager.available(c));
            o.put("hcSupported", HealthShare.supported());
            o.put("share", HealthShare.enabled(c) && HealthShare.granted(c));
            o.put("liveCounter", StepEngine.liveCounter());
            o.put("calibrated", p.contains("calGain"));

            Map<String, Long> days = repo.daysFrom(today.minusDays(60).toString());
            String first = today.toString();
            JSONArray hist = new JSONArray();
            for (Map.Entry<String, Long> e : days.entrySet()) {
                if (e.getKey().compareTo(first) < 0) first = e.getKey();
                if (!e.getKey().equals(today.toString())) hist.put(new JSONArray().put(e.getKey()).put(e.getValue()));
            }
            o.put("history", hist);
            o.put("firstDay", first);
            JSONArray hours = new JSONArray();
            for (long h : repo.hours(today, zone)) hours.put(h);
            o.put("hours", hours);

            StringBuilder dbg = new StringBuilder("Today:");
            for (Map.Entry<String, Long> e : repo.sources(today.toString()).entrySet()) dbg.append(' ').append(e.getKey()).append(' ').append(e.getValue());
            if (today.toString().equals(p.getString("dbgDate", ""))) {
                dbg.append(" · bouts ").append(p.getLong("dbgBouts", 0)).append(": chip ").append(p.getLong("dbgChip", 0))
                        .append(" motion ").append(p.getLong("dbgMotion", 0)).append(" added ").append(p.getLong("dbgAdded", 0));
            }
            o.put("debug", dbg.toString());
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }
}
