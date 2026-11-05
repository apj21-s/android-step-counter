package com.arco.stride;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The app's motion side end to end on real walks: Placement votes during the walk, then
 * steady hand -> peaks + rhythm (StepFusion.handEstimate), anything else -> peaks.
 * (The chip side can't be checked here: these phones logged no step counter.)
 */
public class PipelineCheck {
    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> all = RealSuite.load(a[0]);
        Map<String, double[]> by = new TreeMap<>();
        for (RealSuite.Trace tr : all) {
            MotionCounter c = new MotionCounter();
            Placement p = new Placement();
            long peaks = 0, r0 = 0, rhythm = 0;
            for (int i = 0; i < tr.t.length; i++) {
                float[] v = tr.a[i];
                long rb = c.rhythmSteps;
                int n = c.onSample(v[0], v[1], v[2], tr.t[i]);
                double s = tr.t[i] / 1e9;
                if (s >= tr.start && s <= tr.end + 2) {
                    peaks += n;
                    rhythm += c.rhythmSteps - rb;
                    p.onAccel(v[0], v[1], v[2], tr.t[i]);
                }
            }
            long est = p.boutVote() == Placement.HAND ? StepFusion.handEstimate(peaks, rhythm) : peaks;
            double[] g = by.computeIfAbsent(RealSuite.group(tr.placement), k -> new double[2]);
            g[0] += Math.abs(est - tr.steps) / (double) tr.steps;
            g[1]++;
        }
        double sum = 0, n = 0;
        for (Map.Entry<String, double[]> e : by.entrySet()) {
            System.out.printf(Locale.ROOT, "%-7s n=%3.0f  mean |err| %.1f%%%n", e.getKey(), e.getValue()[1], 100 * e.getValue()[0] / e.getValue()[1]);
            sum += e.getValue()[0]; n += e.getValue()[1];
        }
        System.out.printf(Locale.ROOT, "ALL     n=%3.0f  mean |err| %.1f%%%n", n, 100 * sum / n);
    }
}
