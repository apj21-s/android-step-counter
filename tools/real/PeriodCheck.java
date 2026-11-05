package com.arco.stride;

import java.util.List;
import java.util.Locale;

/** Debug: median step period found during each walk vs the true average period. */
public class PeriodCheck {
    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> traces = RealSuite.load(a[0]);
        for (RealSuite.Trace tr : traces) {
            if (a.length > 1 && !RealSuite.group(tr.placement).equals(a[1])) continue;
            MotionCounter c = new MotionCounter();
            java.util.ArrayList<Float> ps = new java.util.ArrayList<>();
            int walk = 0, tot = 0;
            for (int i = 0; i < tr.t.length; i++) {
                c.onSample(tr.a[i][0], tr.a[i][1], tr.a[i][2], tr.t[i]);
                double s = tr.t[i] / 1e9;
                if (s > tr.start + 3 && s < tr.end - 1 && i % 12 == 0) { tot++; if (c.walking) { walk++; ps.add(c.period); } }
            }
            java.util.Collections.sort(ps);
            float med = ps.isEmpty() ? 0 : ps.get(ps.size() / 2);
            long dbl = ps.stream().filter(p -> p > 1.5 * (tr.end - tr.start) / tr.steps).count();
            System.out.printf(Locale.ROOT, "%-52s true %.2f s  found %.2f s  walking %3.0f%%  doubled %3.0f%%%n", tr.name, (tr.end - tr.start) / tr.steps, med,
                    100.0 * walk / Math.max(1, tot), 100.0 * dbl / Math.max(1, ps.size()));
        }
    }
}
