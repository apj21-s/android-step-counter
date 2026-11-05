package com.arco.stride;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** A bout through StepFusion -> StepEngine.applyBout -> StepRepository, with the Recording API's own buckets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BoutStorageTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final long S = 1_000_000_000L;
    private static final long BASE = LocalDateTime.parse("2026-11-05T09:00").atZone(ZONE).toInstant().toEpochMilli();
    private StepRepository repo;

    @Before public void setUp() { repo = new StepRepository(RuntimeEnvironment.getApplication(), null); }

    private static long wall(long ns) { return BASE + ns / 1_000_000L; }

    /** Walk for 50 s, then a car from 50 s to 400 s with 40 fake chip steps (and `carMotion` fake motion steps). */
    private StepFusion walkThenCar(int place, int walkChipPerS, int walkMotionPerS, int carMotion) {
        StepFusion f = new StepFusion();
        f.vehicleAt = ns -> ns >= 50 * S && ns < 400 * S;
        f.start(S);
        int fakeChip = 0, fakeMotion = 0;
        for (long sec = 1; sec <= 400; sec++) {
            long t = sec * S;
            if (sec <= 50) {
                f.chip(walkChipPerS, t);
                f.motion(walkMotionPerS, t);
            } else {
                if (sec >= 120 && sec % 5 == 0 && fakeChip < 40) { f.chip(1, t); fakeChip++; }
                if (sec >= 100 && sec % 4 == 0 && fakeMotion < carMotion) { f.motion(1, t); fakeMotion++; }
            }
            f.vote(place, t);
            f.tick(t);
        }
        long t = 400 * S;
        while (f.active()) { t += S; f.tick(t); }
        return f;
    }

    /** The Recording API's bucket holds the same chip steps, fake ones included. */
    private void syncRecording(long steps) {
        repo.replaceRange("recording", BASE, BASE + StepRepository.BUCKET_MS, new long[]{BASE}, new long[]{steps}, ZONE, 1);
    }

    @Test public void carStepsAreLeftOutOfTheStoredTotal() {
        syncRecording(100 + 40);                                   // 100 real (walk) + 40 fake (car)
        StepFusion f = walkThenCar(Placement.UPRIGHT, 2, 2, 0);
        long[] r = StepEngine.applyBout(repo, f, BoutStorageTest::wall, ZONE, 2);
        assertEquals(40, r[1]);
        assertEquals(100, repo.day("2026-11-05"));
        syncRecording(100 + 40);                                   // a later sync doesn't bring them back
        assertEquals(100, repo.day("2026-11-05"));
        assertEquals(100, (long) repo.changedForHealth(BASE).get(0)[1]);
    }

    @Test public void phoneFlatInCarAddsNoMotionSteps() {
        // flat phone reads as steady hand: motion would win; 60 fake motion steps happen in the car
        syncRecording(50 + 40);                                    // chip: 50 real + 40 fake
        StepFusion f = walkThenCar(Placement.HAND, 1, 2, 60);      // walk: chip 50, motion 100
        StepEngine.applyBout(repo, f, BoutStorageTest::wall, ZONE, 2);
        assertTrue(f.usedMotion);
        assertEquals(100, f.motionSteps);                          // the car's motion steps never counted
        // stored: chip source 90 - 40 = 50, boost 100 -> 100 (subtracting from the boost would give 60)
        assertEquals(100, repo.day("2026-11-05"));
    }
}
