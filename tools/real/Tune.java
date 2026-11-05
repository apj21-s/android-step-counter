package com.arco.stride;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Grid search of MotionCounter settings on odd-numbered people; the best few are checked on even-numbered people. */
public class Tune {
    static double score(List<RealSuite.Trace> ts, float[] p, boolean rhythm) {
        double sum = 0;
        for (RealSuite.Trace tr : ts) {
            MotionCounter c = make(p);
            double got = RealSuite.runCounter(tr, c, rhythm)[0];
            sum += Math.abs(got - tr.steps) / tr.steps;
        }
        return 100 * sum / ts.size();
    }

    static MotionCounter make(float[] p) {
        MotionCounter c = new MotionCounter();
        c.lineFrac = p[0]; c.refractory = p[1]; c.onThr = p[2]; c.lowHz = p[3]; c.maxStepS = p[4];
        return c;
    }

    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> all = RealSuite.load(a[0]), odd = new ArrayList<>(), even = new ArrayList<>();
        for (RealSuite.Trace tr : all) (Integer.parseInt(tr.uid) % 2 == 1 ? odd : even).add(tr);
        List<float[]> res = new ArrayList<>();
        for (float lf : new float[]{0.45f, 0.55f, 0.65f})
            for (float rf : new float[]{0.5f, 0.6f, 0.7f})
                for (float on : new float[]{0.5f, 0.55f})
                    for (float hz : new float[]{1.8f, 2.2f, 2.5f})
                        for (float mx : new float[]{0.85f}) {
                            float[] p = {lf, rf, on, hz, mx};
                            res.add(new float[]{lf, rf, on, hz, mx, (float) score(odd, p, false)});
                        }
        res.sort((x, y) -> Float.compare(x[5], y[5]));
        System.out.println("lineFrac refractory onThr lowHz maxStepS | tune(odd) | check(even)");
        for (int i = 0; i < 8; i++) {
            float[] r = res.get(i);
            System.out.printf(Locale.ROOT, "%.2f %.2f %.2f %.1f %.2f | %.1f%% | %.1f%%%n", r[0], r[1], r[2], r[3], r[4], r[5], score(even, r, false));
        }
    }
}
