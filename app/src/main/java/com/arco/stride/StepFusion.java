package com.arco.stride;

import java.util.TreeMap;

/**
 * Combines the phone's step chip with our motion detector (plain Java, testable off the phone).
 *
 * Both are placed on a per-second timeline by when the steps happened. Each 15 s window is
 * judged 20 s later (so slow chip reports have arrived). If the motion detector clearly saw
 * walking and the chip saw at least some of it but fewer steps, the missing steps are added.
 *  - Chip sees nothing (car, bus, typing): nothing is added.
 *  - Steps that slipped into the next window are given back if the chip was just late.
 */
final class StepFusion {
    static final int WINDOW_S = 15, LAG_S = 20, MIN_WALK = 10;

    private final TreeMap<Long, long[]> secs = new TreeMap<>();   // second -> {motion, chip}
    private long evalFrom = -1, lastCounterNs, lastAdded;

    void reset() { secs.clear(); evalFrom = -1; lastCounterNs = 0; lastAdded = 0; }

    void motion(int n, long ns) {
        if (evalFrom < 0) evalFrom = sec(ns);
        if (n > 0) bucket(sec(ns))[0] += n;
    }

    /** The chip reported `delta` more steps, the last one at `ns`. Spread them over when they happened. */
    void chip(long delta, long ns) {
        if (delta > 0 && evalFrom >= 0) {
            long s1 = sec(ns), s0;
            if (lastCounterNs == 0 || ns - lastCounterNs > 60_000_000_000L)
                s0 = s1 - Math.min(30, Math.max(0, (delta + 1) / 2 - 1));   // after a pause: ~2 steps per second
            else
                s0 = Math.min(s1, sec(lastCounterNs) + 1);
            long span = s1 - s0 + 1, each = delta / span, rest = delta % span;
            for (long s = s0; s <= s1; s++) bucket(s)[1] += each + (s - s0 < rest ? 1 : 0);
        }
        lastCounterNs = ns;
    }

    /** Judge finished windows. Returns steps to add (negative = give back). */
    long evaluate(long nowNs) {
        long change = 0, nowS = sec(nowNs);
        while (evalFrom >= 0 && nowS - LAG_S >= evalFrom + WINDOW_S) {
            long motion = 0, chip = 0;
            for (long[] v : secs.subMap(evalFrom, evalFrom + WINDOW_S).values()) { motion += v[0]; chip += v[1]; }
            long diff = motion - chip;
            if (diff < 0 && lastAdded > 0) change -= Math.min(-diff, lastAdded);
            double rate = motion / (double) WINDOW_S;
            boolean walking = motion >= MIN_WALK && rate <= 3.4;                         // 40..200 steps a minute
            boolean chipSees = chip >= 0.2 * motion;                                     // not a car or typing
            boolean doubled = rate > 2.6 && chip >= 0.4 * motion && chip <= 0.6 * motion; // 2 bumps per step
            long add = walking && chipSees && !doubled && diff > 0 ? diff : 0;
            change += add;
            lastAdded = add;
            evalFrom += WINDOW_S;
        }
        if (evalFrom >= 0) secs.headMap(evalFrom - 60).clear();
        return change;
    }

    private static long sec(long ns) { return ns / 1_000_000_000L; }

    private long[] bucket(long s) {
        long[] v = secs.get(s);
        if (v == null) { v = new long[2]; secs.put(s, v); }
        return v;
    }
}
