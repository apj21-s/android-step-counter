package com.arco.stride;
import java.util.*;

public class PedoTest {
    interface Sig { double mag(double t); }
    static Random R;

    /** Walking: one step every `period` s (±jit), bounce of `amp` m/s², heel-strike pulse, sensor noise. */
    static int[] walk(double secs, double period, double amp, double jit, double noise, double hz, double asym) {
        List<Double> steps = new ArrayList<>();
        for (double t = 0.7; t < secs; t += period * (1 + jit * (R.nextDouble() * 2 - 1))) steps.add(t);
        double[] st = steps.stream().mapToDouble(Double::doubleValue).toArray();
        Sig s = t -> {
            double v = 0;
            for (int i = 0; i < st.length; i++) {
                double dt = t - st[i];
                if (dt < -0.6 || dt > 0.6) continue;
                double a = amp * (i % 2 == 0 ? 1 + asym : 1 - asym);
                v += a * Math.cos(Math.PI * dt / period) * (Math.abs(dt) < period / 2 ? 1 : 0);   // bounce
                v += 0.6 * a * Math.exp(-dt * dt / (2 * 0.03 * 0.03));                             // heel strike
            }
            return v;
        };
        return new int[]{run(s, secs, noise, hz), st.length};
    }

    static int run(Sig s, double secs, double noise, double hz) {
        AccelPedometer p = new AccelPedometer();
        int total = 0;
        double theta = 0.4;
        for (double t = 0; t < secs; t += 1 / hz) {
            theta += 0.002 * Math.sin(t);                          // phone slowly tilting in the hand
            double m = 9.81 + s.mag(t) + noise * R.nextGaussian();
            float x = (float) (m * Math.sin(theta)), z = (float) (m * Math.cos(theta)), y = (float) (0.1 * R.nextGaussian());
            total += p.onSample(x, y, z, (long) (t * 1e9) + 1);
        }
        return total;
    }

    static void report(String name, int got, int truth) {
        System.out.printf(Locale.US, "%-38s counted %4d / real %4d  -> %s%n", name, got, truth,
            truth == 0 ? (got == 0 ? "ok" : got + " false steps") : String.format(Locale.US, "%.1f%%", 100.0 * got / truth));
    }

    public static void main(String[] a) {
        for (double hz : new double[]{50, 25}) {
            System.out.println("== " + (int) hz + " samples/s");
            R = new Random(1);
            int[] r;
            r = walk(120, 0.55, 2.5, 0.05, 0.15, hz, 0.15); report("walk, arm swinging", r[0], r[1]);
            r = walk(120, 0.55, 0.7, 0.05, 0.15, hz, 0.10); report("walk, phone held steady", r[0], r[1]);
            r = walk(120, 0.55, 0.45, 0.06, 0.15, hz, 0.10); report("walk, held very steady (weak bounce)", r[0], r[1]);
            r = walk(120, 0.85, 0.6, 0.07, 0.15, hz, 0.10); report("slow walk, held steady", r[0], r[1]);
            r = walk(120, 0.36, 3.5, 0.04, 0.2, hz, 0.10); report("running", r[0], r[1]);
            r = walk(30, 0.55, 0.8, 0.05, 0.15, hz, 0.10); report("short walk (30 s)", r[0], r[1]);
            report("sitting still", run(t -> 0, 120, 0.05, hz), 0);
            R = new Random(2);
            double[] bumps = new double[200]; for (int i = 0; i < 200; i++) bumps[i] = R.nextDouble() * 120;
            report("fidgeting (random bumps)", run(t -> { double v = 0; for (double b : bumps) { double d = t - b; v += 2.0 * Math.exp(-d * d / 0.003); } return v; }, 120, 0.1, hz), 0);
            double[] taps = new double[480]; for (int i = 0; i < 480; i++) taps[i] = R.nextDouble() * 120;
            report("typing on the phone", run(t -> { double v = 0; for (double b : taps) { double d = t - b; v += 0.8 * Math.exp(-d * d / 0.0004); } return v; }, 120, 0.08, hz), 0);
            report("shaking the phone (5 per s)", run(t -> 5 * Math.sin(2 * Math.PI * 5 * t), 20, 0.2, hz), 0);
            double[] ar = {0};
            report("car ride (road noise)", run(t -> { ar[0] = 0.9 * ar[0] + 0.25 * R.nextGaussian(); return ar[0]; }, 120, 0.1, hz), 0);
            report("car on bumpy road (1.4/s sway)", run(t -> 0.5 * Math.sin(2 * Math.PI * 1.4 * t + 0.3 * Math.sin(0.2 * t)) + (ar[0] = 0.9 * ar[0] + 0.25 * R.nextGaussian()), 120, 0.1, hz), 0);
        }
    }
}
