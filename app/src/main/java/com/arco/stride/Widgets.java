package com.arco.stride;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The four home-screen widgets. Each is drawn into one bitmap so the thin rings,
 * glow and fonts match the design exactly (RemoteViews can't draw these directly).
 */
public final class Widgets {
    public static class Ring extends Base { int kind() { return 0; } }
    public static class Minimal extends Base { int kind() { return 1; } }
    public static class Wide extends Base { int kind() { return 2; } }
    public static class Big extends Base { int kind() { return 3; } }

    public abstract static class Base extends AppWidgetProvider {
        abstract int kind();
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c, m, ids, kind()); }
        @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
            update(c, m, new int[]{id}, kind());
        }
    }

    private static final Class<?>[] KINDS = {Ring.class, Minimal.class, Wide.class, Big.class};
    private static final int[][] DEFAULT_DP = {{150, 150}, {150, 70}, {320, 70}, {320, 150}};
    private static final Map<String, Typeface> FONTS = new HashMap<>();

    // palette slots
    private static final int BG = 0, BORDER = 1, FG = 2, FG2 = 3, TRACK = 4, A1 = 5, A2 = 6;

    private Widgets() {}

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int k = 0; k < KINDS.length; k++) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, KINDS[k]));
            if (ids.length > 0) update(c, m, ids, k);
        }
    }

    static void update(Context c, AppWidgetManager m, int[] ids, int kind) {
        float d = Math.min(c.getResources().getDisplayMetrics().density, 3f);
        long steps = StepEngine.today(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (int id : ids) {
            Bundle o = m.getAppWidgetOptions(id);
            int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            if (w <= 0 || h <= 0) { w = DEFAULT_DP[kind][0]; h = DEFAULT_DP[kind][1]; }
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setImageViewBitmap(R.id.img, render(c, kind, w, h, d));
            v.setContentDescription(R.id.img, fmt(steps) + " steps today");
            v.setOnClickPendingIntent(R.id.root, open);
            m.updateAppWidget(id, v);
        }
    }

    // ---------- drawing ----------

    static Bitmap render(Context c, int kind, int wDp, int hDp, float d) {
        int W = Math.max(1, Math.round(wDp * d)), H = Math.max(1, Math.round(hDp * d));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int[] p = palette(c);
        long s = StepEngine.today(c);
        int goal = StepStore.goal(c);
        float pc = Math.min(1f, s / (float) goal);
        String pct = (int) Math.floor(s * 100.0 / goal) + "%";
        if (kind != 1) card(cv, W, H, p, radius(c, d));
        switch (kind) {
            case 0: drawRing(c, cv, W, H, d, p, s, pc, pct); break;
            case 1: drawMinimal(c, cv, W, H, d, p, s, pc); break;
            case 2: drawWide(c, cv, W, H, d, p, s, pc, goal); break;
            default: drawBig(c, cv, W, H, d, p, s, pc, pct, goal); break;
        }
        return b;
    }

    private static void drawRing(Context c, Canvas cv, int W, int H, float d, int[] p, long s, float pc, String pct) {
        float S = Math.min(W, H), k = clamp(S / (150 * d), 0.8f, 1.35f);
        float cx = W / 2f, cy = H / 2f, r = S * 0.38f;
        ring(cv, cx, cy, r, 3.4f * d, pc, p, d);
        cv.drawText("STEPS", cx, cy - 20 * k * d, text(c, "Inter-600", 8.5f * k * d, p[FG2], Paint.Align.CENTER, 0.24f));
        Paint n = text(c, "SpaceGrotesk-400", 30 * k * d, p[FG], Paint.Align.CENTER, -0.035f);
        fit(n, fmt(s), r * 1.55f);
        cv.drawText(fmt(s), cx, cy + 10 * k * d, n);
        cv.drawText(pct, cx, cy + 27 * k * d, text(c, "Inter-500", 10.5f * k * d, p[FG2], Paint.Align.CENTER, 0));
    }

    private static void drawMinimal(Context c, Canvas cv, int W, int H, float d, int[] p, long s, float pc) {
        float pad = 8 * d, k = clamp(H / (70 * d), 0.7f, 1.4f);
        Paint n = text(c, "SpaceGrotesk-400", 34 * k * d, 0xFFFFFFFF, Paint.Align.LEFT, -0.04f);
        n.setShadowLayer(8 * d, 0, 2 * d, 0x59000000);
        float L = 28 * k * d, gap = 9 * d;
        fit(n, fmt(s), W - 2 * pad - L - gap);
        float base = H / 2f + 8 * k * d;
        logo(c, cv, pad, base - n.getTextSize() * 0.36f - L / 2f, L, p);
        cv.drawText(fmt(s), pad + L + gap, base, n);
        line(cv, pad, W - pad, base + 14 * k * d, 1.5f * d, pc, 0x477F7F7F, p, d);
    }

    private static void drawWide(Context c, Canvas cv, int W, int H, float d, int[] p, long s, float pc, int goal) {
        float pad = Math.min(24 * d, W * 0.07f), base = H / 2f + 2 * d;
        Paint label = text(c, "Inter-600", 8.5f * d, p[FG2], Paint.Align.RIGHT, 0.24f);
        String of = "OF " + fmt(goal);
        Paint n = text(c, "SpaceGrotesk-400", 28 * d, p[FG], Paint.Align.LEFT, -0.035f);
        fit(n, fmt(s), W - 2 * pad - label.measureText(of) - 12 * d);
        cv.drawText(fmt(s), pad, base, n);
        cv.drawText(of, W - pad, base, label);
        line(cv, pad, W - pad, H / 2f + 18 * d, 2 * d, pc, p[TRACK], p, d);
    }

    private static void drawBig(Context c, Canvas cv, int W, int H, float d, int[] p, long s, float pc, String pct, int goal) {
        float pad = 24 * d, D = Math.min(138 * d, H - 24 * d), cx = W - 20 * d - D / 2f, cy = H / 2f;
        // dial with 12 ticks
        Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeWidth(Math.max(1f, D * 0.008f));
        tick.setColor(withAlpha(p[FG2], 0.45f));
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0 * 2 * Math.PI;
            float r1 = D * 0.475f, r2 = D * (i % 3 == 0 ? 0.5f : 0.49f);
            cv.drawLine(cx + r1 * (float) Math.sin(a), cy - r1 * (float) Math.cos(a), cx + r2 * (float) Math.sin(a), cy - r2 * (float) Math.cos(a), tick);
        }
        ring(cv, cx, cy, D * 0.42f, 3.4f * d, pc, p, d);
        cv.drawText(pct, cx, cy + 6 * d, text(c, "SpaceGrotesk-400", 17 * d, p[FG], Paint.Align.CENTER, -0.02f));

        float maxW = cx - D / 2f - pad - 12 * d;
        cv.drawText("TODAY", pad, 22 * d + 8 * d, text(c, "Inter-600", 8.5f * d, p[FG2], Paint.Align.LEFT, 0.24f));
        Paint n = text(c, "SpaceGrotesk-400", 40 * d, p[FG], Paint.Align.LEFT, -0.04f);
        fit(n, fmt(s), maxW);
        float base = H / 2f + 12 * d;
        cv.drawText(fmt(s), pad, base, n);
        String sub = String.format(Locale.US, "%.1f km · %,d kcal", StepStore.km(c, s), StepStore.kcal(s));
        Paint sp = text(c, "Inter-500", 10.5f * d, p[FG2], Paint.Align.LEFT, 0);
        fit(sp, sub, maxW);
        cv.drawText(sub, pad, base + 20 * d, sp);

        long[] wk = StepStore.week(c);
        float y = H - 25 * d;
        for (int i = 0; i < 7; i++) {
            float x = pad + 3 * d + i * 13 * d;
            boolean hit = wk[i] >= goal, today = i == 6;
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (today && !hit) {
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(1.5f * d);
                dot.setColor(p[A2]);
                cv.drawCircle(x, y, 2.25f * d, dot);
            } else {
                dot.setColor(today ? p[A2] : hit ? p[A1] : p[TRACK]);
                if (today) dot.setShadowLayer(4 * d, 0, 0, p[A2]);
                cv.drawCircle(x, y, 3 * d, dot);
            }
        }
    }

    // ---------- pieces ----------

    /** The footprints logo, filled with the accent gradient. */
    private static void logo(Context c, Canvas cv, float x, float y, float size, int[] p) {
        android.graphics.drawable.Drawable dr = c.getDrawable(R.drawable.ic_feet);
        if (dr == null) return;
        RectF box = new RectF(x, y, x + size, y + size);
        int layer = cv.saveLayer(box, null);
        dr.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        dr.draw(cv);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        g.setShader(new LinearGradient(x, y, x + size, y + size, p[A2], p[A1], Shader.TileMode.CLAMP));
        cv.drawRect(box, g);
        cv.restoreToCount(layer);
    }

    private static void card(Canvas cv, int W, int H, int[] p, float radius) {
        RectF r = new RectF(0.5f, 0.5f, W - 0.5f, H - 0.5f);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(p[BG]);
        cv.drawRoundRect(r, radius, radius, f);
        if ((p[BORDER] >>> 24) == 0) return;
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
        b.setStyle(Paint.Style.STROKE);
        b.setStrokeWidth(1);
        b.setColor(p[BORDER]);
        cv.drawRoundRect(r, radius, radius, b);
    }

    private static void ring(Canvas cv, float cx, float cy, float r, float sw, float pc, int[] p, float d) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setStyle(Paint.Style.STROKE);
        t.setStrokeWidth(sw);
        t.setColor(p[TRACK]);
        cv.drawCircle(cx, cy, r, t);
        if (pc <= 0.004f) return;
        Paint a = new Paint(Paint.ANTI_ALIAS_FLAG);
        a.setStyle(Paint.Style.STROKE);
        a.setStrokeWidth(sw);
        a.setStrokeCap(Paint.Cap.ROUND);
        a.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, p[A2], p[A1], Shader.TileMode.CLAMP));
        cv.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90, 360 * pc, false, a);
        double ang = Math.toRadians(-90 + 360 * pc);
        tip(cv, cx + r * (float) Math.cos(ang), cy + r * (float) Math.sin(ang), d, p[A2]);
    }

    private static void line(Canvas cv, float x0, float x1, float y, float h, float pc, int track, int[] p, float d) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(track);
        cv.drawRoundRect(new RectF(x0, y - h / 2, x1, y + h / 2), h / 2, h / 2, t);
        if (pc <= 0.004f) return;
        float xe = x0 + (x1 - x0) * pc;
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setShader(new LinearGradient(x0, y, x1, y, p[A1], p[A2], Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(x0, y - h / 2, xe, y + h / 2), h / 2, h / 2, f);
        tip(cv, xe, y, d, p[A2]);
    }

    private static void tip(Canvas cv, float x, float y, float d, int glow) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setColor(0xFFFFFFFF);
        g.setShadowLayer(5 * d, 0, 0, glow);
        cv.drawCircle(x, y, 3.1f * d, g);
    }

    private static Paint text(Context c, String font, float px, int color, Paint.Align al, float spacing) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        t.setTypeface(font(c, font));
        t.setTextSize(px);
        t.setColor(color);
        t.setTextAlign(al);
        t.setLetterSpacing(spacing);
        return t;
    }

    /** Shrink text until it fits the given width. */
    private static void fit(Paint t, String s, float maxW) {
        float w = t.measureText(s);
        if (w > maxW && maxW > 0) t.setTextSize(t.getTextSize() * maxW / w);
    }

    private static synchronized Typeface font(Context c, String name) {
        Typeface t = FONTS.get(name);
        if (t == null) {
            try { t = Typeface.createFromAsset(c.getAssets(), "ttf/" + name + ".ttf"); }
            catch (RuntimeException e) { t = Typeface.DEFAULT; }
            FONTS.put(name, t);
        }
        return t;
    }

    private static float radius(Context c, float d) {
        if (Build.VERSION.SDK_INT >= 31) {
            try { return c.getResources().getDimension(android.R.dimen.system_app_widget_background_radius); }
            catch (RuntimeException ignored) { }
        }
        return 26 * d;
    }

    private static int[] palette(Context c) {
        int bg = 0xEB121212, border = 0x14FFFFFF, fg = 0xF2FFFFFF, fg2 = 0x8CFFFFFF, track = 0x14FFFFFF;
        int a1 = StepStore.a1(c), a2 = StepStore.a2(c);
        switch (StepStore.style(c)) {
            case "amoled": bg = 0xFF000000; border = 0x12FFFFFF; break;
            case "frosted": bg = 0x521C1A1E; border = 0x2EFFFFFF; break;
            case "you":
                if (Build.VERSION.SDK_INT >= 31) {
                    bg = c.getColor(android.R.color.system_neutral2_800);
                    fg = c.getColor(android.R.color.system_accent1_50);
                    fg2 = c.getColor(android.R.color.system_accent2_200);
                    track = withAlpha(fg, 0.12f);
                    a1 = c.getColor(android.R.color.system_accent1_300);
                    a2 = c.getColor(android.R.color.system_accent1_100);
                    border = 0;
                }
                break;
            default: break;
        }
        return new int[]{bg, border, fg, fg2, track, a1, a2};
    }

    private static int withAlpha(int color, float a) { return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static String fmt(long n) { return String.format(Locale.US, "%,d", n); }
}
