package com.arco.stride;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays real labelled walks (Cambridge ubicomp13 dataset, converted to 25 Hz by convert.py)
 * through the motion detector. Steps confirmed between walk start and end + 2 s are scored
 * against the video-counted truth; steps outside that window are reported as "extra".
 * Usage: RealSuite <dir with index.csv>
 */
public class RealSuite {
    static final class Trace {
        String name, uid, placement;
        int steps;
        double start, end;
        long[] t;
        float[][] a, g;
    }

    interface Counter { double[] run(Trace tr); }   // returns {steps in walk, steps outside}

    static List<Trace> load(String dir) throws IOException {
        List<Trace> out = new ArrayList<>();
        List<String> idx = Files.readAllLines(Path.of(dir, "index.csv"));
        for (String line : idx.subList(1, idx.size())) {
            String[] f = line.split(",");
            Trace tr = new Trace();
            tr.name = f[0]; tr.uid = f[1]; tr.placement = f[3];
            tr.steps = Integer.parseInt(f[4]);
            tr.start = Double.parseDouble(f[5]); tr.end = Double.parseDouble(f[6]);
            List<String> rows = Files.readAllLines(Path.of(dir, f[0] + ".csv"));
            int n = rows.size();
            tr.t = new long[n]; tr.a = new float[n][3]; tr.g = new float[n][3];
            for (int i = 0; i < n; i++) {
                String[] r = rows.get(i).split(",");
                tr.t[i] = Long.parseLong(r[0]);
                for (int k = 0; k < 3; k++) { tr.a[i][k] = Float.parseFloat(r[1 + k]); tr.g[i][k] = Float.parseFloat(r[4 + k]); }
            }
            out.add(tr);
        }
        return out;
    }

    static double[] runPedometer(Trace tr, AccelPedometer p) {
        double in = 0, outside = 0;
        for (int i = 0; i < tr.t.length; i++) {
            int n = p.onSample(tr.a[i][0], tr.a[i][1], tr.a[i][2], tr.t[i]);
            double s = tr.t[i] / 1e9;
            if (s >= tr.start && s <= tr.end + 2) in += n; else outside += n;
        }
        return new double[]{in, outside};
    }

    static double[] runCounter(Trace tr, MotionCounter p, boolean rhythm) {
        double in = 0, outside = 0;
        for (int i = 0; i < tr.t.length; i++) {
            long r0 = p.rhythmSteps;
            int n = p.onSample(tr.a[i][0], tr.a[i][1], tr.a[i][2], tr.t[i]);
            if (rhythm) n = (int) (p.rhythmSteps - r0);
            double s = tr.t[i] / 1e9;
            if (s >= tr.start && s <= tr.end + 2) in += n; else outside += n;
        }
        return new double[]{in, outside};
    }

    /** Prints per-placement accuracy; returns overall mean absolute error (%). */
    static double report(String label, List<Trace> traces, Counter c, boolean verbose) {
        Map<String, List<double[]>> by = new TreeMap<>();
        for (Trace tr : traces) {
            double[] r = c.run(tr);
            if (tr.steps == 0) {                 // a lab "car / bus" recording: every step is fake
                System.out.printf(Locale.ROOT, "  %-55s zero-step recording: %.0f fake steps%n", tr.name, r[0] + r[1]);
                continue;
            }
            double err = 100.0 * (r[0] - tr.steps) / tr.steps;
            by.computeIfAbsent(group(tr.placement), k -> new ArrayList<>()).add(new double[]{err, r[1], r[0] / tr.steps});
            if (verbose) System.out.printf(Locale.ROOT, "  %-55s truth %3d got %5.0f (%+.1f%%) extra %.0f%n", tr.name, tr.steps, r[0], err, r[1]);
        }
        System.out.println("== " + label);
        double all = 0; int n = 0;
        for (Map.Entry<String, List<double[]>> e : by.entrySet()) {
            double mae = 0, bias = 0, extra = 0, worst = 0;
            for (double[] v : e.getValue()) { mae += Math.abs(v[0]); bias += v[0]; extra += v[1]; worst = Math.max(worst, Math.abs(v[0])); }
            int k = e.getValue().size();
            all += mae; n += k;
            System.out.printf(Locale.ROOT, "  %-14s n=%3d  mean |err| %5.1f%%  bias %+5.1f%%  worst %5.1f%%  extra/trace %4.1f%n",
                    e.getKey(), k, mae / k, bias / k, worst, extra / k);
        }
        System.out.printf(Locale.ROOT, "  %-14s n=%3d  mean |err| %5.1f%%%n", "ALL", n, all / n);
        return all / n;
    }

    static String group(String placement) {
        switch (placement) {
            case "Hand_held": return "hand";
            case "Handheld_using": return "typing";
            case "Backpack": case "Handbag": return "bag";
            default: return "pocket";
        }
    }

    public static void main(String[] args) throws IOException {
        List<Trace> traces = load(args[0]);
        boolean v = args.length > 1 && args[1].equals("-v");
        List<Trace> odd = new ArrayList<>(), even = new ArrayList<>();
        for (Trace tr : traces) (Integer.parseInt(tr.uid) % 2 == 1 ? odd : even).add(tr);
        report("old AccelPedometer (v2.0, 25 Hz)", traces, tr -> runPedometer(tr, new AccelPedometer()), v);
        report("MotionCounter peaks", traces, tr -> runCounter(tr, new MotionCounter(), false), v);
        report("MotionCounter rhythm", traces, tr -> runCounter(tr, new MotionCounter(), true), v);
    }
}
