package com.arco.stride;
import java.util.*;

public class FusionTest {
    // segment: kind, seconds, step period, bounce amp, chip catch rate
    record Seg(String kind, double secs, double period, double amp, double chipP) {}

    public static void main(String[] a) {
        List<Seg> plan = List.of(
            new Seg("walk", 180, 0.55, 0.7, 0.6),    // held steady: chip misses 40%
            new Seg("still", 30, 0, 0, 0),
            new Seg("car", 180, 0, 0, 0),            // swaying car: chip counts nothing
            new Seg("walk", 180, 0.55, 2.5, 0.98),   // arm swinging: chip is good
            new Seg("type", 60, 0, 0, 0),            // typing while sitting
            new Seg("walk", 120, 0.55, 0.45, 0.3),   // held very steady: chip misses 70%
            new Seg("walk", 120, 0.85, 0.6, 0.7));   // slow walk held steady
        Random R = new Random(Long.parseLong(a[0]));
        double t0 = 0;
        List<double[]> steps = new ArrayList<>();     // {time, segmentIndex}
        double[] segStart = new double[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            Seg s = plan.get(i); segStart[i] = t0;
            if (s.kind.equals("walk"))
                for (double t = t0 + 0.7; t < t0 + s.secs; t += s.period * (1 + 0.05 * (R.nextDouble() * 2 - 1))) steps.add(new double[]{t, i});
            t0 += s.secs;
        }
        double end = t0;
        double[] taps = new double[400]; for (int i = 0; i < taps.length; i++) taps[i] = R.nextDouble() * 60;

        // chip: counts each step with chance p, reports a batch every 3-10 s (timestamp = last counted step)
        List<double[]> chipEvents = new ArrayList<>();   // {reportTime, delta, lastStepTime}
        double nextReport = 5; long pending = 0; double lastCounted = 0;
        int si = 0;
        for (double t = 0; t < end + 30; t += 0.05) {
            while (si < steps.size() && steps.get(si)[0] <= t) {
                Seg s = plan.get((int) steps.get(si)[1]);
                if (R.nextDouble() < s.chipP) { pending++; lastCounted = steps.get(si)[0]; }
                si++;
            }
            if (t >= nextReport) { if (pending > 0) chipEvents.add(new double[]{t, pending, lastCounted}); pending = 0; nextReport = t + 3 + 7 * R.nextDouble(); }
        }

        AccelPedometer p = new AccelPedometer();
        StepFusion f = new StepFusion();
        long chipTotal = 0, added = 0, motionTotal = 0;
        int ci = 0, idx = 0; double ar = 0, theta = 0.4, lastEval = 0;
        double hz = 50;
        for (double t = 0; t < end + 60; t += 1 / hz) {
            while (idx < plan.size() - 1 && t >= segStart[idx + 1]) idx++;
            Seg s = plan.get(idx);
            double v = 0;
            ar = 0.9 * ar + 0.25 * R.nextGaussian();
            if (t < end) switch (s.kind) {
                case "walk" -> {
                    for (double[] st : steps) {
                        if ((int) st[1] != idx) continue;
                        double d = t - st[0]; if (d < -0.6 || d > 0.6) continue;
                        v += s.amp * (Math.abs(d) < s.period / 2 ? Math.cos(Math.PI * d / s.period) : 0) + 0.6 * s.amp * Math.exp(-d * d / 0.0018);
                    }
                }
                case "car" -> v = 0.5 * Math.sin(2 * Math.PI * 1.4 * t) + ar;
                case "type" -> { for (double b : taps) { double d = t - segStart[idx] - b; v += 0.8 * Math.exp(-d * d / 0.0004); } }
                default -> { }
            }
            theta += 0.002 * Math.sin(t);
            double m = 9.81 + v + 0.15 * R.nextGaussian();
            long ns = (long) (t * 1e9) + 1;
            int n = p.onSample((float) (m * Math.sin(theta)), (float) (0.1 * R.nextGaussian()), (float) (m * Math.cos(theta)), ns);
            motionTotal += n;
            f.motion(n, ns);
            while (ci < chipEvents.size() && chipEvents.get(ci)[0] <= t) {
                double[] ce = chipEvents.get(ci++);
                chipTotal += (long) ce[1];
                f.chip((long) ce[1], (long) (ce[2] * 1e9));
            }
            if (t - lastEval >= 5) { lastEval = t; added += f.evaluate(ns); }
        }
        long truth = steps.size();
        System.out.printf(Locale.US, "real steps        %d%n", truth);
        System.out.printf(Locale.US, "chip only         %d  (%.1f%%)%n", chipTotal, 100.0 * chipTotal / truth);
        System.out.printf(Locale.US, "motion detector   %d  (includes car/typing false steps)%n", motionTotal);
        System.out.printf(Locale.US, "Stride (combined) %d  (%.1f%%)  added %d%n", chipTotal + added, 100.0 * (chipTotal + added) / truth, added);
    }
}
