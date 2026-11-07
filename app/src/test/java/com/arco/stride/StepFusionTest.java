package com.arco.stride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Bout decisions by phone position and vehicle. Plain JUnit: StepFusion has no Android code. */
public class StepFusionTest {
    private static final long S = 1_000_000_000L;

    /** A 100 s bout: chip reports `chipPerS` steps a second, motion `motionPerS`; then it closes. */
    private static StepFusion bout(int place, boolean inVehicle, boolean arKnown, int chipPerS, int motionPerS) {
        StepFusion f = new StepFusion();
        if (arKnown) f.vehicleAt = ns -> inVehicle;
        long t = 1000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) {
            t += S;
            f.motion(motionPerS, t);
            if (chipPerS > 0) f.chip(chipPerS, t);
            f.vote(place, t);
            f.tick(t);
        }
        for (int i = 0; i < 100 && f.active(); i++) { t += S; f.tick(t); }
        assertFalse("bout should close", f.active());
        return f;
    }

    @Test public void pocketTrustsTheChip() {
        StepFusion f = bout(Placement.UPRIGHT, false, true, 2, 3);
        assertFalse(f.usedMotion);
        f = bout(Placement.COVERED, false, true, 2, 3);
        assertFalse(f.usedMotion);
        f = bout(Placement.SWING, false, true, 2, 3);
        assertFalse(f.usedMotion);
    }

    @Test public void steadyHandTrustsMotionWhenHigher() {
        StepFusion f = bout(Placement.HAND, false, true, 1, 2);
        assertTrue(f.usedMotion);
        // motion said 200, chip 100: the cap lets motion add at most 5% -> 105
        assertEquals(200, f.motionSteps);
        assertEquals(105, f.storedMotion);
        assertTrue(f.capped);
        assertEquals(105, f.motionBySecond.values().stream().mapToLong(Long::longValue).sum());
        f = bout(Placement.HAND, false, true, 2, 1);         // chip higher: keep the chip
        assertFalse(f.usedMotion);
    }

    @Test public void steadyHandKeepsMotionWhenChipSeesLittleButNoVehicle() {
        // chip saw ~10%: the old 20% rule would refuse, but Activity Recognition says not in a vehicle
        StepFusion f = new StepFusion();
        f.vehicleAt = ns -> false;
        long t = 1000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) {
            t += S;
            f.motion(2, t);
            if (i % 5 == 0) f.chip(1, t);
            f.vote(Placement.HAND, t);
            f.tick(t);
        }
        while (f.active()) { t += S; f.tick(t); }
        assertTrue(f.usedMotion);
        // without Activity Recognition the 20% rule still applies
        f = new StepFusion();
        t = 5000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) {
            t += S;
            f.motion(2, t);
            if (i % 5 == 0) f.chip(1, t);
            f.vote(Placement.HAND, t);
            f.tick(t);
        }
        while (f.active()) { t += S; f.tick(t); }
        assertFalse(f.usedMotion);
    }

    @Test public void steadyHandUsesRhythmWhenPeaksAreFaint() {
        assertEquals(150, StepFusion.handEstimate(100, 150));   // peaks >10% low: faint, trust rhythm
        assertEquals(103, StepFusion.handEstimate(100, 105));   // close: average
        assertEquals(100, StepFusion.handEstimate(100, 0));     // no rhythm yet: peaks
        StepFusion f = new StepFusion();
        f.vehicleAt = ns -> false;
        long t = 1000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) {
            t += S;
            f.motion(i % 2, t);          // 50 faint peaks
            f.rhythm(2, t);              // rhythm says 200
            f.chip(1, t);                // chip 100
            f.vote(Placement.HAND, t);
            f.tick(t);
        }
        while (f.active()) { t += S; f.tick(t); }
        assertTrue(f.usedMotion);
        assertEquals(200, f.motionSteps);
        assertEquals(105, f.motionBySecond.values().stream().mapToLong(Long::longValue).sum());   // capped at chip + 5%
    }

    @Test public void topUpCapFollowsCalibration() {
        StepFusion f = new StepFusion();
        f.vehicleAt = ns -> false;
        f.maxTopUp = 0.20f;                                    // a phone whose chip missed 18% on its calibration walk
        long t = 1000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) { t += S; f.motion(2, t); f.chip(1, t); f.vote(Placement.HAND, t); f.tick(t); }
        while (f.active()) { t += S; f.tick(t); }
        assertEquals(120, f.storedMotion);
        // small real corrections pass untouched: chip 100, motion 103 (under the 5% default)
        f = new StepFusion();
        f.vehicleAt = ns -> false;
        t = 5000 * S;
        f.start(t);
        for (int i = 0; i < 100; i++) { t += S; f.motion(i < 3 ? 2 : 1, t); f.chip(1, t); f.vote(Placement.HAND, t); f.tick(t); }
        while (f.active()) { t += S; f.tick(t); }
        assertTrue(f.usedMotion);
        assertEquals(103, f.storedMotion);
        assertFalse(f.capped);
    }

    @Test public void noChipStepsMeansNoMotionSteps() {
        // phone bumping on a table: motion "steps" but the chip saw none
        StepFusion f = bout(Placement.HAND, false, true, 0, 1);
        assertFalse(f.usedMotion);
        assertEquals(0, f.storedMotion);
    }

    @Test public void vehicleTakesTheChipStepsBack() {
        StepFusion f = bout(Placement.UNKNOWN, true, true, 1, 1);
        assertTrue(f.vehicle);
        assertFalse(f.usedMotion);
        // the whole 100 s bout is inside the ride (the predicate says vehicle at all times)
        assertEquals(100, f.chipBySecond.values().stream().mapToLong(Long::longValue).sum());
        f = bout(Placement.UNKNOWN, true, false, 1, 1);      // no Activity Recognition: nothing taken back
        assertFalse(f.vehicle);
    }

    @Test public void stepsJustAfterGettingOffAreKept() {
        // bus from 1000 s to 1120 s (Activity Recognition), then a 60 s walk in the same bout
        long busEnd = 1120 * S;
        StepFusion f = new StepFusion();
        f.vehicleAt = ns -> ns >= 1000 * S && ns < busEnd;
        long t = 1000 * S;
        f.start(t);
        for (int i = 0; i < 180; i++) {
            t += S;
            f.chip(2, t);                                  // fake on the bus, real afterwards
            f.vote(Placement.UPRIGHT, t);
            f.tick(t);
        }
        while (f.active()) { t += S; f.tick(t); }
        assertTrue(f.vehicle);
        // only seconds with 30 s of bus on both sides: 1030..1089 -> 60 s x 2 steps
        assertEquals(120, f.chipBySecond.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1030L, (long) f.chipBySecond.firstKey());
        assertEquals(1089L, (long) f.chipBySecond.lastKey());
        // the walk after the bus is judged normally (chip kept, not removed)
        assertEquals(240, f.chipSteps);
    }

    @Test public void unknownPositionKeepsOldMaxRule() {
        assertTrue(bout(Placement.UNKNOWN, false, false, 1, 2).usedMotion);
        assertFalse(bout(Placement.UNKNOWN, false, false, 2, 1).usedMotion);
    }

    @Test public void agreementIsDetectedForLearning() {
        assertTrue(bout(Placement.UPRIGHT, false, true, 2, 2).agreed);
        assertFalse(bout(Placement.UPRIGHT, false, true, 1, 2).agreed);
    }
}
