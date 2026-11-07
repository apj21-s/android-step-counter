package com.arco.stride;

import java.util.Map;
import java.util.TreeMap;

/**
 * One walking bout at a time: chip steps vs motion-detector steps, never added together.
 * Plain Java (no Android classes) so it can be tested on a computer.
 *
 * A bout starts at the first step. It stops 20 s after the last step (or after 120 s with no
 * chip steps at all, e.g. in a car), then waits 15 s more so late chip reports still land in it.
 * When it closes, by where the phone was for most of the bout (Placement):
 *   seconds in a vehicle (Activity Recognition, with 30 s of vehicle on both sides) -> the chip's
 *     steps in those seconds are taken back; the rest of the bout is judged as below
 *   pocket / bag / upright / swinging hand                -> chip (it catches nearly every step there)
 *   steady hand (screen up)                               -> motion, if higher than the chip
 *   unknown                                               -> max(chip, motion)  (the old rule)
 * Motion is also refused when it looks like two bumps per step (about double the chip), and,
 * only when Activity Recognition isn't available, when the chip saw under 20% of it.
 * Only when motion wins do we hand out extra steps, placed at the seconds they happened.
 * Everything here uses the sensor clock (nanoseconds since boot).
 */
final class StepFusion {
    // 20 s (was 60 s): on a phone the motion sensor runs until the bout stops, and most of its
    // on-time was this tail after short moves. A longer pause just starts a new bout at the next step.
    static final long STOP_NS = 20_000_000_000L;
    static final long GRACE_NS = 15_000_000_000L;
    static final long NO_CHIP_NS = 120_000_000_000L;

    static final int IDLE = 0, WALKING = 1, STOPPED = 2, CLOSED = 3;
    /** A second counts as vehicle only with vehicle this long before and after it (late reports, walking off a bus). */
    static final long VEHICLE_MARGIN_NS = 30_000_000_000L;

    private final TreeMap<Long, long[]> secs = new TreeMap<>();   // second -> {motion peaks, chip, rhythm}
    private boolean active, stopped;
    private long lastStepNs, lastChipNs, lastCounterNs, stopNs;

    float gain = 1f;                        // from calibration: motion steps × gain
    /** Activity Recognition's answer for a moment (sensor clock ns); null when it isn't available. */
    java.util.function.LongPredicate vehicleAt;

    private final int[] votes = new int[5];
    private long lastVoteS = -1;

    // result of the last closed bout
    long chipSteps, motionSteps;
    boolean usedMotion, vehicle, agreed;
    int place;
    final TreeMap<Long, Long> motionBySecond = new TreeMap<>();   // filled only when motion won
    final TreeMap<Long, Long> chipBySecond = new TreeMap<>();     // filled only in a vehicle: steps to take back

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
        java.util.Arrays.fill(votes, 0);
        lastVoteS = -1;
    }

    /** Once a second while walking: where the phone is now. */
    void vote(int placement, long ns) {
        long sec = ns / 1_000_000_000L;
        if (!active || stopped || sec == lastVoteS) return;
        lastVoteS = sec;
        votes[placement]++;
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

    /** The motion detector's rhythm count (walking time ÷ step period) added `n` steps at `ns`. */
    void rhythm(int n, long ns) {
        if (!active || stopped || n <= 0) return;
        bucket(ns / 1_000_000_000L)[2] += n;
    }

    /**
     * Steady-hand estimate from peaks and rhythm: their average, or rhythm alone when peaks are
     * more than 10% lower (faint steps). Best of the options tried on the Cambridge walks.
     */
    static long handEstimate(long peaks, long rhythm) {
        if (rhythm <= 0) return peaks;
        return peaks < 0.9 * rhythm ? rhythm : Math.round((peaks + rhythm) / 2.0);
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

    private boolean vehicleSecond(long sec) {
        long ns = sec * 1_000_000_000L;
        return vehicleAt.test(ns) && vehicleAt.test(ns - VEHICLE_MARGIN_NS) && vehicleAt.test(ns + VEHICLE_MARGIN_NS);
    }

    private void close() {
        // vehicle seconds first: their chip steps are taken back and they're left out of everything else
        chipBySecond.clear();
        vehicle = false;
        if (vehicleAt != null) {
            // every vehicle second leaves the bout: chip steps there are taken back, motion steps there never count
            java.util.Iterator<Map.Entry<Long, long[]>> it = secs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, long[]> e = it.next();
                if (!vehicleSecond(e.getKey())) continue;
                if (e.getValue()[1] > 0) chipBySecond.put(e.getKey(), e.getValue()[1]);
                it.remove();
                vehicle = true;
            }
        }
        long motionRaw = 0, chip = 0, rhythmRaw = 0, first = -1, last = -1;
        for (Map.Entry<Long, long[]> e : secs.entrySet()) {
            motionRaw += e.getValue()[0];
            chip += e.getValue()[1];
            rhythmRaw += e.getValue()[2];
            if (e.getValue()[0] > 0) {
                if (first < 0) first = e.getKey();
                last = e.getKey();
            }
        }
        place = Placement.UNKNOWN;
        for (int i = 1; i < votes.length; i++) if (votes[i] > votes[place]) place = i;
        boolean useRhythm = place == Placement.HAND && rhythmRaw > 0;
        long motion = Math.round((useRhythm ? handEstimate(motionRaw, rhythmRaw) : motionRaw) * gain);
        double rate = first < 0 ? 0 : motion / (double) (last - first + 1);

        boolean chipSees = chip >= 0.2 * motion;
        boolean doubled = rate > 2.6 && chip >= 0.4 * motion && chip <= 0.6 * motion;
        chipSteps = chip;
        motionSteps = motion;
        boolean arKnown = vehicleAt != null;
        agreed = chip > 20 && Math.abs(motion - chip) <= 0.05 * chip;
        if (place == Placement.HAND) {
            usedMotion = motion > chip && !doubled && (arKnown || chipSees);
        } else if (place == Placement.UNKNOWN) {
            usedMotion = motion > chip && chipSees && !doubled;
        } else {
            usedMotion = false;                     // covered, upright, swinging: the chip is better there
        }


        motionBySecond.clear();
        if (usedMotion) {
            // spread the bout's motion count over the seconds it happened, in proportion to the channel used
            int ch = useRhythm ? 2 : 0;
            long chTotal = useRhythm ? rhythmRaw : motionRaw;
            double per = chTotal == 0 ? 0 : motion / (double) chTotal;
            double carry = 0;
            for (Map.Entry<Long, long[]> e : secs.entrySet()) {
                if (e.getValue()[ch] == 0) continue;
                carry += e.getValue()[ch] * per;
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
            v = new long[3];
            secs.put(s, v);
        }
        return v;
    }
}
