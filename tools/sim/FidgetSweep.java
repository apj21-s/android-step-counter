package com.arco.stride;

import java.util.List;
import java.util.Locale;

/** How strict the gap-evenness check must be: fake steps in the sim vs error on real walks. */
public class FidgetSweep {
    public static void main(String[] a) throws Exception {
        SimSuite.R = new java.util.Random(1);
        List<SimSuite.Scenario> sims = SimSuite.scenarios();
        List<RealSuite.Trace> real = RealSuite.load(a[0]);
        for (float cv : new float[]{0.25f, 0.3f, 0.35f, 0.45f}) {
            int fake = 0;
            for (SimSuite.Scenario s : sims) if (s.steps.length == 0 && !s.name.contains("bumpy")) fake += sim(s, cv);
            double mae = 0;
            for (RealSuite.Trace tr : real) {
                MotionCounter c = new MotionCounter(); c.maxGapCv = cv;
                mae += Math.abs(RealSuite.runCounter(tr, c, false)[0] - tr.steps) / tr.steps;
            }
            System.out.printf(Locale.ROOT, "maxGapCv %.2f  sim fake steps (no bumpy road) %3d  real mean |err| %.1f%%%n", cv, fake, 100 * mae / real.size());
        }
    }

    static int sim(SimSuite.Scenario s, float cv) {
        SimSuite.Sig sig = s.sig.get();
        java.util.Random r = new java.util.Random(13);
        MotionCounter mc = new MotionCounter(); mc.maxGapCv = cv;
        double theta = 0.4; int total = 0;
        for (double t = 0; t < s.secs; t += 1 / 25.0) {
            theta += 0.002 * Math.sin(t);
            float[] v = SimSuite.sample(9.81 + sig.at(t) + 0.15 * r.nextGaussian(), theta, r);
            total += mc.onSample(v[0], v[1], v[2], SimSuite.ns(t));
        }
        return total;
    }
}
