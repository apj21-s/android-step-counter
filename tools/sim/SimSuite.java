package com.arco.stride;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Simulated walking: old build vs new build.
 *
 * Old build: always-on 50 Hz motion detector + "every 15 s add what the chip missed".
 * New build: motion detector at 25 Hz, switched on only during walking bouts (started by the
 *            step detector), and bout steps = max(chip, motion).
 *
 * Chip model: counts each real step with chance p (lower when the phone is held steady),
 * reports in batches every 5-10 s, and only reports steps that are at least 10 s old.
 */
public class SimSuite {
    interface Sig { double at(double t); }

    static final class Scenario {
        String name;
        double secs;           // total length including still padding
        double[] steps = {};   // real step times (s)
        double chipP;          // chance the chip counts a step
        Supplier<Sig> sig;
        int vehicleChipPerMin; // false chip steps per minute (vehicles)
    }

    static Random R;
    static final double PAD = 20, TAIL = 160;

    // ---------- scenarios ----------

    static Scenario walk(String name, double secs, double period, double amp, double chipP, double asym) {
        List<Double> list = new ArrayList<>();
        for (double t = PAD + 0.7; t < PAD + secs; t += period * (1 + 0.05 * (R.nextDouble() * 2 - 1))) list.add(t);
        double[] st = new double[list.size()];
        for (int i = 0; i < st.length; i++) st[i] = list.get(i);
        Scenario s = new Scenario();
        s.name = name; s.secs = PAD + secs + TAIL; s.steps = st; s.chipP = chipP;
        s.sig = () -> t -> {
            int i = Arrays.binarySearch(st, t - 0.6);
            if (i < 0) i = -i - 1;
            double v = 0;
            for (; i < st.length && st[i] <= t + 0.6; i++) {
                double d = t - st[i];
                double a = amp * (i % 2 == 0 ? 1 + asym : 1 - asym);
                if (Math.abs(d) < period / 2) v += a * Math.cos(Math.PI * d / period);   // body bounce
                v += 0.6 * a * Math.exp(-d * d / (2 * 0.03 * 0.03));                    // heel strike
            }
            return v;
        };
        return s;
    }

    static Scenario other(String name, double secs, Supplier<Sig> inner, int vehicleChipPerMin) {
        Scenario s = new Scenario();
        s.name = name; s.secs = PAD + secs + TAIL; s.chipP = 0; s.vehicleChipPerMin = vehicleChipPerMin;
        s.sig = () -> { Sig in = inner.get(); return t -> (t >= PAD && t < PAD + secs) ? in.at(t - PAD) : 0; };
        return s;
    }

    static List<Scenario> scenarios() {
        List<Scenario> l = new ArrayList<>();
        l.add(walk("walk, arm swinging", 120, 0.55, 2.5, 0.98, 0.15));
        l.add(walk("walk, phone held steady", 120, 0.55, 0.7, 0.60, 0.10));
        l.add(walk("held very steady (weak bounce)", 120, 0.55, 0.45, 0.30, 0.10));
        l.add(walk("slow walk, held steady", 120, 0.85, 0.6, 0.70, 0.10));
        l.add(walk("running", 120, 0.36, 3.5, 0.98, 0.10));
        l.add(walk("short walk (30 s)", 30, 0.55, 0.8, 0.90, 0.10));
        l.add(other("sitting still", 120, () -> t -> 0, 0));
        double[] bumps = new double[200];
        for (int i = 0; i < bumps.length; i++) bumps[i] = R.nextDouble() * 120;
        l.add(other("fidgeting (random bumps)", 120, () -> t -> {
            double v = 0;
            for (double b : bumps) { double d = t - b; v += 2.0 * Math.exp(-d * d / 0.003); }
            return v;
        }, 0));
        double[] taps = new double[480];
        for (int i = 0; i < taps.length; i++) taps[i] = R.nextDouble() * 120;
        l.add(other("typing on the phone", 120, () -> t -> {
            double v = 0;
            for (double b : taps) { double d = t - b; v += 0.8 * Math.exp(-d * d / 0.0004); }
            return v;
        }, 0));
        l.add(other("shaking the phone (5 per s)", 20, () -> t -> 5 * Math.sin(2 * Math.PI * 5 * t), 0));
        l.add(other("car ride (road noise)", 120, () -> {
            Random r = new Random(5); double[] ar = {0};
            return t -> { ar[0] = 0.9 * ar[0] + 0.25 * r.nextGaussian(); return ar[0]; };
        }, 2));
        l.add(other("car on bumpy road (1.4/s sway)", 120, () -> {
            Random r = new Random(6); double[] ar = {0};
            return t -> { ar[0] = 0.9 * ar[0] + 0.25 * r.nextGaussian(); return 0.5 * Math.sin(2 * Math.PI * 1.4 * t + 0.3 * Math.sin(0.2 * t)) + ar[0]; };
        }, 2));
        return l;
    }

    static Scenario mixedDay() {
        Scenario[] parts = {
            walk("", 180, 0.55, 0.7, 0.6, 0.1), other("", 30, () -> t -> 0, 0),
            other("", 180, () -> { Random r = new Random(9); double[] ar = {0};
                return t -> { ar[0] = 0.9 * ar[0] + 0.25 * r.nextGaussian(); return 0.5 * Math.sin(2 * Math.PI * 1.4 * t) + ar[0]; }; }, 2),
            walk("", 180, 0.55, 2.5, 0.98, 0.15), walk("", 120, 0.55, 0.45, 0.3, 0.1), walk("", 120, 0.85, 0.6, 0.7, 0.1) };
        // join: each part keeps its own time base, shifted to follow the previous one
        Scenario s = new Scenario();
        s.name = "mixed 14-min day";
        List<Double> st = new ArrayList<>();
        List<double[]> chipPs = new ArrayList<>();   // {stepTime, p}
        double[] offs = new double[parts.length];
        double t0 = 0;
        for (int i = 0; i < parts.length; i++) {
            offs[i] = t0;
            for (double x : parts[i].steps) { st.add(x + t0); chipPs.add(new double[]{x + t0, parts[i].chipP}); }
            t0 += parts[i].secs - TAIL + 10;
        }
        s.secs = t0 + TAIL;
        s.steps = st.stream().mapToDouble(d -> d).toArray();
        s.chipP = -1;   // per-step probabilities below
        MIXED_P = chipPs;
        final double total = t0;
        s.sig = () -> {
            Sig[] sigs = new Sig[parts.length];
            for (int i = 0; i < parts.length; i++) sigs[i] = parts[i].sig.get();
            return t -> {
                if (t >= total) return 0;
                int k = 0;
                while (k + 1 < parts.length && t >= offs[k + 1]) k++;
                return sigs[k].at(t - offs[k]);
            };
        };
        s.vehicleChipPerMin = 0;
        return s;
    }
    static List<double[]> MIXED_P;

    // ---------- chip model ----------

    /** Steps the chip counts (by real time). */
    static double[] chipCounted(Scenario s, Random r) {
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < s.steps.length; i++) {
            double p = s.chipP >= 0 ? s.chipP : MIXED_P.get(i)[1];
            if (r.nextDouble() < p) c.add(s.steps[i]);
        }
        if (s.vehicleChipPerMin > 0)
            for (double t = PAD; t < s.secs - TAIL; t += 60.0 / s.vehicleChipPerMin) c.add(t + r.nextDouble());
        c.sort(Double::compare);
        double[] out = new double[c.size()];
        for (int i = 0; i < out.length; i++) out[i] = c.get(i);
        return out;
    }

    /** Batches: {deliveryTime, delta, timestamp}. Timestamp is the last step's time, or the delivery time (worst case). */
    static List<double[]> chipEvents(double[] counted, double end, double delay, boolean stampDelivery, Random r) {
        List<double[]> ev = new ArrayList<>();
        int i = 0;
        for (double t = 5; t < end + 60; t += 5 + 5 * r.nextDouble()) {
            int n = 0;
            double last = 0;
            while (i < counted.length && counted[i] <= t - delay) { last = counted[i]; i++; n++; }
            if (n > 0) ev.add(new double[]{t, n, stampDelivery ? t : last});
        }
        return ev;
    }

    // ---------- the two builds ----------

    static long ns(double t) { return (long) (t * 1e9) + 1; }

    static float[] sample(double m, double theta, Random r) {
        return new float[]{(float) (m * Math.sin(theta)), (float) (0.1 * r.nextGaussian()), (float) (m * Math.cos(theta))};
    }

    /** Old: motion detector always on at 50 Hz + window add. Returns {total, accelOnSeconds}. */
    static long[] runOld(Scenario s, double[] counted, List<double[]> events) {
        OldAccelPedometer p = new OldAccelPedometer();
        OldStepFusion f = new OldStepFusion();
        Sig sig = s.sig.get();
        Random r = new Random(11);
        long chip = 0, added = 0;
        int ei = 0;
        double lastEval = 0, theta = 0.4, hz = 50;
        for (double t = 0; t < s.secs + 60; t += 1 / hz) {
            theta += 0.002 * Math.sin(t);
            float[] a = sample(9.81 + sig.at(t) + 0.15 * r.nextGaussian(), theta, r);
            int n = p.onSample(a[0], a[1], a[2], ns(t));
            f.motion(n, ns(t));
            while (ei < events.size() && events.get(ei)[0] <= t) {
                double[] e = events.get(ei++);
                chip += (long) e[1];
                f.chip((long) e[1], ns(e[2]));
            }
            if (t - lastEval >= 5) { lastEval = t; added += f.evaluate(ns(t)); }
        }
        return new long[]{chip + added, (long) (s.secs + 60)};
    }

    /** New: motion detector on only during bouts, 25 Hz, bout max rule, 15-min buckets. Returns {total, accelOnSeconds, bouts}. */
    static long[] runNew(Scenario s, double[] counted, List<double[]> events) {
        MotionCounter p = new MotionCounter();
        StepFusion f = new StepFusion();
        Sig sig = s.sig.get();
        Random r = new Random(12);
        TreeMap<Long, long[]> buckets = new TreeMap<>();   // 15-min bucket -> {chip, boost}
        for (double c : counted) buckets.computeIfAbsent((long) (c / 900), k -> new long[2])[0]++;
        boolean accelOn = false;
        int ei = 0, di = 0;
        long bouts = 0;
        double accelOnSec = 0, lastTick = 0, theta = 0.4, hz = 25;
        for (double t = 0; t < s.secs + 60; t += 1 / hz) {
            // step detector (fast, ~1 s after a step the chip counts) starts or resumes a bout
            while (di < counted.length && counted[di] + 1 <= t) {
                if (!f.active()) bouts++;
                if (!f.active() || f.stopped()) {
                    f.start(ns(t));
                    if (!accelOn) { accelOn = true; p.reset(); }
                }
                di++;
            }
            while (ei < events.size() && events.get(ei)[0] <= t) {
                double[] e = events.get(ei++);
                f.chip((long) e[1], ns(e[2]));
            }
            theta += 0.002 * Math.sin(t);
            double m = 9.81 + sig.at(t) + 0.15 * r.nextGaussian();
            if (accelOn) {
                float[] a = sample(m, theta, r);
                f.motion(p.onSample(a[0], a[1], a[2], ns(t)), ns(t));
                accelOnSec += 1 / hz;
            }
            if (t - lastTick >= 1) {
                lastTick = t;
                int st = f.tick(ns(t));
                if (st == StepFusion.STOPPED) accelOn = false;
                if (st == StepFusion.CLOSED) {
                    accelOn = false;
                    for (var e : f.motionBySecond.entrySet())
                        buckets.computeIfAbsent(e.getKey() / 900, k -> new long[2])[1] += e.getValue();
                }
            }
        }
        long total = 0;
        for (long[] b : buckets.values()) total += Math.max(b[0], b[1]);
        return new long[]{total, Math.round(accelOnSec), bouts};
    }

    /** Old detector alone at 50 Hz, new detector alone at 25 Hz (no chip). */
    static int detectorOnly(Scenario s, boolean old) { return detectorOnly(s, old ? 0 : 1); }

    /** which: 0 = old 50 Hz, 1 = AccelPedometer, 2 = MotionCounter */
    static int detectorOnly(Scenario s, int which) {
        boolean old = which == 0;
        MotionCounter mc = new MotionCounter();
        Sig sig = s.sig.get();
        Random r = new Random(13);
        OldAccelPedometer po = new OldAccelPedometer();
        AccelPedometer pn = new AccelPedometer();
        double hz = old ? 50 : 25, theta = 0.4;
        int total = 0;
        for (double t = 0; t < s.secs; t += 1 / hz) {
            theta += 0.002 * Math.sin(t);
            float[] a = sample(9.81 + sig.at(t) + 0.15 * r.nextGaussian(), theta, r);
            total += old ? po.onSample(a[0], a[1], a[2], ns(t)) : which == 1 ? pn.onSample(a[0], a[1], a[2], ns(t)) : mc.onSample(a[0], a[1], a[2], ns(t));
        }
        return total;
    }

    static String pct(long got, long truth) {
        if (truth == 0) return got == 0 ? "0 fake" : "+" + got + " fake";
        return String.format(Locale.US, "%.1f%%", 100.0 * got / truth);
    }

    public static void main(String[] args) {
        R = new Random(1);
        List<Scenario> list = scenarios();
        System.out.println("== Whole system (chip: batches every 5-10 s, 10 s late, misses steps when held steady)");
        System.out.printf(Locale.US, "%-32s %6s %10s %10s %10s %14s%n", "scenario", "real", "chip only", "old build", "new build", "motion on (s)");
        for (Scenario s : list) {
            Random cr = new Random(21);
            double[] counted = chipCounted(s, cr);
            List<double[]> ev = chipEvents(counted, s.secs, 10, false, cr);
            long[] o = runOld(s, counted, ev), n = runNew(s, counted, ev);
            long truth = s.steps.length;
            System.out.printf(Locale.US, "%-32s %6d %10s %10s %10s %6d -> %-5d%n", s.name, truth,
                pct(counted.length - (s.vehicleChipPerMin > 0 ? 0 : 0), truth), pct(o[0], truth), pct(n[0], truth), o[1], n[1]);
        }

        System.out.println();
        System.out.println("== Motion detector alone, no chip (old at 50 Hz, AccelPedometer and MotionCounter at 25 Hz)");
        for (Scenario s : list)
            System.out.printf(Locale.US, "%-32s %6d %10s %10s %10s%n", s.name, s.steps.length,
                pct(detectorOnly(s, 0), s.steps.length), pct(detectorOnly(s, 1), s.steps.length), pct(detectorOnly(s, 2), s.steps.length));

        System.out.println();
        System.out.println("== Late chip: counts every step perfectly, 10 s late, timestamps = delivery time (worst case)");
        R = new Random(3);
        for (Scenario s : new Scenario[]{walk("walk, phone held steady", 300, 0.55, 0.7, 1.0, 0.1), walk("walk, arm swinging", 300, 0.55, 2.5, 1.0, 0.15)}) {
            Random cr = new Random(22);
            double[] counted = chipCounted(s, cr);
            List<double[]> ev = chipEvents(counted, s.secs, 10, true, cr);
            long[] o = runOld(s, counted, ev), n = runNew(s, counted, ev);
            System.out.printf(Locale.US, "%-32s real %d  chip %d  old %d (%s)  new %d (%s)%n", s.name, s.steps.length,
                counted.length, o[0], pct(o[0], s.steps.length), n[0], pct(n[0], s.steps.length));
        }

        System.out.println();
        System.out.println("== Mixed 14-minute day (steady walk, car, swinging walk, very steady, slow), 6 seeds");
        for (int seed = 1; seed <= 6; seed++) {
            R = new Random(seed);
            Scenario s = mixedDay();
            Random cr = new Random(100 + seed);
            double[] counted = chipCounted(s, cr);
            List<double[]> ev = chipEvents(counted, s.secs, 10, false, cr);
            long[] o = runOld(s, counted, ev), n = runNew(s, counted, ev);
            System.out.printf(Locale.US, "seed %d: real %d  chip %s  old %s  new %s  motion on %ds of %ds (old: all of it)%n", seed,
                s.steps.length, pct(counted.length, s.steps.length), pct(o[0], s.steps.length), pct(n[0], s.steps.length), n[1], (long) s.secs);
        }
    }
}
