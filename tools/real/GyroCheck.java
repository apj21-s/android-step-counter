package com.arco.stride;

import java.util.List;
import java.util.Locale;

/** Does the gyroscope help count steady-hand walks? Peaks from accel, from gyro, and from both. */
public class GyroCheck {
    static double[] run(RealSuite.Trace tr, float gyroScale, boolean useAccel) {
        MotionCounter c = new MotionCounter();
        double in = 0;
        for (int i = 0; i < tr.t.length; i++) {
            float[] a = tr.a[i], g = tr.g[i];
            float gm = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            int n;
            if (useAccel && gyroScale == 0) n = c.onSample(a[0], a[1], a[2], tr.t[i]);
            else {
                float am = useAccel ? (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) - 9.81f : 0;
                n = c.onSample(0, 0, 9.81f + am + gyroScale * gm, tr.t[i]);
            }
            double s = tr.t[i] / 1e9;
            if (s >= tr.start && s <= tr.end + 2) in += n;
        }
        return new double[]{in};
    }

    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> all = RealSuite.load(a[0]);
        Object[][] variants = {{"accel only", 0f, true}, {"gyro only (x3)", 3f, false}, {"accel + gyro (x1)", 1f, true}, {"accel + gyro (x3)", 3f, true}};
        for (String grp : new String[]{"hand", "typing", "pocket", "bag"}) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%-7s", grp));
            for (Object[] v : variants) {
                double mae = 0; int n = 0;
                for (RealSuite.Trace tr : all) {
                    if (!RealSuite.group(tr.placement).equals(grp)) continue;
                    mae += Math.abs(run(tr, (float) v[1], (boolean) v[2])[0] - tr.steps) / tr.steps; n++;
                }
                sb.append(String.format(Locale.ROOT, " | %s %5.1f%%", v[0], 100 * mae / n));
            }
            System.out.println(sb);
        }
    }
}
