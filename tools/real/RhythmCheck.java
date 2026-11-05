package com.arco.stride;

import java.util.List;
import java.util.Locale;

/** Steady-hand walks: peak count vs rhythm count (time ÷ period) vs simple combinations. */
public class RhythmCheck {
    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> all = RealSuite.load(a[0]);
        String[] names = {"peaks", "rhythm", "mean", "rhythm if peaks low by >10%"};
        for (String grp : new String[]{"hand", "typing", "pocket", "bag"}) {
            double[] mae = new double[names.length]; int n = 0;
            for (RealSuite.Trace tr : all) {
                if (!RealSuite.group(tr.placement).equals(grp)) continue;
                double p = RealSuite.runCounter(tr, new MotionCounter(), false)[0];
                double r = RealSuite.runCounter(tr, new MotionCounter(), true)[0];
                double[] est = {p, r, (p + r) / 2, p < 0.9 * r ? r : p};
                for (int k = 0; k < est.length; k++) mae[k] += Math.abs(est[k] - tr.steps) / tr.steps;
                n++;
            }
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%-7s", grp));
            for (int k = 0; k < names.length; k++) sb.append(String.format(Locale.ROOT, " | %s %4.1f%%", names[k], 100 * mae[k] / n));
            System.out.println(sb);
        }
    }
}
