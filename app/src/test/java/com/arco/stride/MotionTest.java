package com.arco.stride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** MotionCounter and Placement on synthetic signals (real-walk scores: tools/real/run.sh). */
public class MotionTest {
    private static final long NS = 40_000_000L;     // 25 Hz

    /** Walk: a bounce per step plus a heel strike, phone tilted `tilt` from flat. Returns peak steps. */
    private static int walk(MotionCounter c, Placement p, int steps, double period, double amp, double tilt, boolean stride) {
        Random r = new Random(4);
        int got = 0;
        long t = 1_000_000_000L;
        double secs = steps * period + 6;
        for (double s = 0; s < secs; s += 0.04, t += NS) {
            double ph = (s - 2) / period, v = 0;
            if (s > 2 && ph < steps) {
                v = amp * Math.cos(2 * Math.PI * ph);
                if (stride && ((int) ph) % 2 == 1) v *= 0.5;          // pocket: other leg's step is weaker
            }
            double m = 9.81 + v + 0.05 * r.nextGaussian();
            float x = (float) (m * Math.sin(tilt)), z = (float) (m * Math.cos(tilt));
            got += c.onSample(x, 0, z, t);
            if (p != null) p.onAccel(x, 0, z, t);
        }
        return got;
    }

    @Test public void countsAnEvenWalk() {
        int n = walk(new MotionCounter(), null, 100, 0.55, 2.0, 0.3, false);
        assertTrue("got " + n, Math.abs(n - 100) <= 4);
    }

    @Test public void countsBothLegsInAPocket() {
        // one strong and one weak step per stride: the old detector counted about half of these
        int n = walk(new MotionCounter(), null, 100, 0.55, 2.5, 1.4, true);
        assertTrue("got " + n, Math.abs(n - 100) <= 6);
    }

    @Test public void stillAndShakingAreNotSteps() {
        MotionCounter c = new MotionCounter();
        Random r = new Random(1);
        int got = 0;
        long t = 1_000_000_000L;
        for (int i = 0; i < 25 * 60; i++, t += NS) got += c.onSample(0, 0, (float) (9.81 + 0.05 * r.nextGaussian()), t);
        for (int i = 0; i < 25 * 20; i++, t += NS) got += c.onSample(0, 0, (float) (9.81 + 5 * Math.sin(2 * Math.PI * 5 * i * 0.04)), t);
        assertEquals(0, got);
    }

    @Test public void placementScreenUpIsHandUprightIsNot() {
        Placement hand = new Placement(), pocket = new Placement();
        walk(new MotionCounter(), hand, 60, 0.55, 1.0, 0.4, false);      // tilted 23° from flat, screen up
        walk(new MotionCounter(), pocket, 60, 0.55, 2.5, 1.5, true);     // nearly upright
        assertEquals(Placement.HAND, hand.boutVote());
        assertEquals(Placement.UPRIGHT, pocket.boutVote());
    }

    @Test public void placementCoveredWins() {
        Placement p = new Placement();
        p.onProximity(true, 0);
        walk(new MotionCounter(), p, 40, 0.55, 1.0, 0.4, false);
        assertEquals(Placement.COVERED, p.boutVote());
    }
}
