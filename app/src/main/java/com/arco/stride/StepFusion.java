package com.arco.stride;

import java.util.Map;
import java.util.TreeMap;

/**
 * One walking bout at a time: chip steps vs motion-detector steps, never added together.
 * Plain Java (no Android classes) so it can be tested on a computer.
 *
 * A bout starts at the first step. It stops 60 s after the last step (or after 120 s with no
 * chip steps at all, e.g. in a car), then waits 15 s more so late chip reports still land in it.
 * When it closes:
 *   bout steps = max(chip, motion)
 *   but if chip < 20% of motion, keep chip (motion alone is probably a vehicle or typing)
 *   and if motion looks like two bumps per step (too fast, about double the chip), keep chip.
 * Only when motion wins do we hand out extra steps, placed at the seconds they happened.
 * Everything here uses the sensor clock (nanoseconds since boot).
 */
final class StepFusion {
    static final long STOP_NS = 60_000_000_000L;
    static final long GRACE_NS = 15_000_000_000L;
    static final long NO_CHIP_NS = 120_000_000_000L;

    static final int IDLE = 0, WALKING = 1, STOPPED = 2, CLOSED = 3;

    private final TreeMap<Long, long[]> secs = new TreeMap<>();   // second -> {motion, chip}
    private boolean active, stopped;
    private long lastStepNs, lastChipNs, lastCounterNs, stopNs;

    float gain = 1f;                        // from calibration: motion steps × gain

    // result of the last closed bout
    long chipSteps, motionSteps;
    boolean usedMotion;
    final TreeMap<Long, Long> motionBySecond = new TreeMap<>();   // filled only when motion won

    boolean active() { return active; }
    boolean stopped() { return stopped; }

    /** A step was noticed (detector, significant motion, or a step during the grace period). */
    void start(long ns) {
        if (active) {
            if (stopped) { stopped = false; lastStepNs = ns; }   // walking again before the bout closed
            return;
        }
        active = true;
        stopped = false;
        lastStepNs = ns;
        lastChipNs = ns;
        secs.clear();
    }

    /** The step counter reported `delta` more steps, the last one at `ns`. */
    void chip(long delta, long ns) {
        if (active && delta > 0) {
            long s1 = ns / 1_000_000_000L;
            long s0;
            if (lastCounterNs == 0 || ns - lastCounterNs > 60_000_000_000L) {
                s0 = s1 - Math.min(30, Math.max(0, (delta + 1) / 2 - 1));   // after a pause: ~2 steps a second
            } else {
                s0 = Math.min(s1, lastCounterNs / 1_000_000_000L + 1);
            }
            long span = s1 - s0 + 1;
            for (long s = s0; s <= s1; s++) {
                long share = delta / span + (s - s0 < delta % span ? 1 : 0);
                bucket(s)[1] += share;
            }
            lastChipNs = ns;
            if (ns > lastStepNs) lastStepNs = ns;
            if (stopped && ns > stopNs) stopped = false;          // a step after we stopped: still walking
        }
        lastCounterNs = ns;
    }

    /** The motion detector confirmed `n` steps at `ns`. */
    void motion(int n, long ns) {
        if (!active || stopped || n <= 0) return;
        bucket(ns / 1_000_000_000L)[0] += n;
        lastStepNs = ns;
    }

    /** Call regularly. Returns WALKING, STOPPED (turn the accelerometer off), CLOSED (read the result) or IDLE. */
    int tick(long nowNs) {
        if (!active) return IDLE;
        if (!stopped) {
            if (nowNs - lastStepNs >= STOP_NS || nowNs - lastChipNs >= NO_CHIP_NS) {
                stopped = true;
                stopNs = nowNs;
                return STOPPED;
            }
            return WALKING;
        }
        if (nowNs - stopNs < GRACE_NS) return STOPPED;
        close();
        return CLOSED;
    }

    private void close() {
        long motionRaw = 0, chip = 0, first = -1, last = -1;
        for (Map.Entry<Long, long[]> e : secs.entrySet()) {
            motionRaw += e.getValue()[0];
            chip += e.getValue()[1];
            if (e.getValue()[0] > 0) {
                if (first < 0) first = e.getKey();
                last = e.getKey();
            }
        }
        long motion = Math.round(motionRaw * gain);
        double rate = first < 0 ? 0 : motion / (double) (last - first + 1);

        boolean chipSees = chip >= 0.2 * motion;
        boolean doubled = rate > 2.6 && chip >= 0.4 * motion && chip <= 0.6 * motion;
        chipSteps = chip;
        motionSteps = motion;
        usedMotion = motion > chip && chipSees && !doubled;

        motionBySecond.clear();
        if (usedMotion) {
            double carry = 0;
            for (Map.Entry<Long, long[]> e : secs.entrySet()) {
                if (e.getValue()[0] == 0) continue;
                carry += e.getValue()[0] * gain;
                long whole = Math.round(carry);
                if (whole > 0) { motionBySecond.put(e.getKey(), whole); carry -= whole; }
            }
        }
        active = false;
        stopped = false;
        secs.clear();
    }

    private long[] bucket(long s) {
        long[] v = secs.get(s);
        if (v == null) {
            v = new long[2];
            secs.put(s, v);
        }
        return v;
    }
}
