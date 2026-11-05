package com.arco.stride;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Placement votes over each real walk, by true placement. */
public class PlaceCheck {
    public static void main(String[] a) throws Exception {
        Map<String, int[]> by = new TreeMap<>();
        Map<String, float[]> sw = new TreeMap<>();
        for (RealSuite.Trace tr : RealSuite.load(a[0])) {
            Placement p = new Placement();
            for (int i = 0; i < tr.t.length; i++) {
                double s = tr.t[i] / 1e9;
                if (s < tr.start) continue;
                if (s > tr.end) break;
                p.onAccel(tr.a[i][0], tr.a[i][1], tr.a[i][2], tr.t[i]);
                if (i % 25 == 0) { float[] v = sw.computeIfAbsent(tr.placement, k -> new float[2]); v[0] += p.swingRad(); v[1]++; }
            }
            by.computeIfAbsent(tr.placement, k -> new int[5])[p.boutVote()]++;
        }
        System.out.println("true placement          -> walks voted unknown/hand/covered/swing/upright   mean swing (rad)");
        for (Map.Entry<String, int[]> e : by.entrySet())
            System.out.printf(Locale.ROOT, "%-22s %s   %.2f%n", e.getKey(), java.util.Arrays.toString(e.getValue()), sw.get(e.getKey())[0] / sw.get(e.getKey())[1]);
    }
}
