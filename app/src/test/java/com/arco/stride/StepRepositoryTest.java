package com.arco.stride;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StepRepositoryTest {
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");      // UTC+5:30
    private static final ZoneId KATHMANDU = ZoneId.of("Asia/Kathmandu");  // UTC+5:45
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York"); // DST
    private StepRepository repo;

    @Before public void setUp() {
        repo = new StepRepository(RuntimeEnvironment.getApplication(), null);   // in-memory database
    }

    private static long at(String localDateTime, ZoneId zone) {
        return LocalDateTime.parse(localDateTime).atZone(zone).toInstant().toEpochMilli();
    }

    @Test public void repeatedSyncsDoNotChangeTotals() {
        long from = at("2026-11-05T00:00", KOLKATA), to = at("2026-11-06T00:00", KOLKATA);
        long[] starts = {at("2026-11-05T08:00", KOLKATA), at("2026-11-05T08:15", KOLKATA), at("2026-11-05T18:30", KOLKATA)};
        long[] steps = {400, 350, 1200};
        for (int i = 0; i < 5; i++) repo.replaceRange("recording", from, to, starts, steps, KOLKATA, 1000 + i);
        assertEquals(1950, repo.day("2026-11-05"));
        // a later sync with a bigger value for one bucket replaces it, it does not add
        repo.replaceRange("recording", from, to, starts, new long[]{400, 360, 1200}, KOLKATA, 2000);
        assertEquals(1960, repo.day("2026-11-05"));
    }

    @Test public void counterResetAfterReboot() {
        assertEquals(0, repo.counterDelta(5000, 7));      // first reading: nothing to add yet
        assertEquals(120, repo.counterDelta(5120, 7));
        assertEquals(0, repo.counterDelta(5120, 7));      // same value again
        assertEquals(30, repo.counterDelta(30, 8));       // rebooted: counter restarted, 30 steps since boot
        assertEquals(15, repo.counterDelta(45, 8));
        assertEquals(9000, repo.counterDelta(9000, 9));   // rebooted and already walked more than before
        assertEquals(10, repo.counterDelta(10, -1));      // boot count unknown: value went down = reboot
    }

    @Test public void halfHourZoneDayBoundary() {
        // 23:45 and 00:00 local in India are different days, though only 15 minutes apart
        repo.addSteps("counter", at("2026-11-05T23:50", KOLKATA), 100, KOLKATA, 1);
        repo.addSteps("counter", at("2026-11-06T00:05", KOLKATA), 40, KOLKATA, 1);
        assertEquals(100, repo.day("2026-11-05"));
        assertEquals(40, repo.day("2026-11-06"));
        assertEquals(at("2026-11-06T00:00", KOLKATA), StepRepository.bucketStart(at("2026-11-06T00:05", KOLKATA)));
    }

    @Test public void quarterHourZoneDayBoundary() {
        repo.addSteps("counter", at("2026-11-05T23:59", KATHMANDU), 7, KATHMANDU, 1);
        repo.addSteps("counter", at("2026-11-06T00:00", KATHMANDU), 9, KATHMANDU, 1);
        assertEquals(7, repo.day("2026-11-05"));
        assertEquals(9, repo.day("2026-11-06"));
    }

    @Test public void dstDaysHave23And25Hours() {
        assertEquals(23, repo.hours(LocalDate.parse("2026-03-08"), NEW_YORK).length);   // clocks go forward
        assertEquals(25, repo.hours(LocalDate.parse("2026-11-01"), NEW_YORK).length);   // clocks go back
        // 01:30 happens twice on the fall-back day; both count, in different hours
        long first = LocalDateTime.parse("2026-11-01T01:30").atZone(NEW_YORK).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
        long second = LocalDateTime.parse("2026-11-01T01:30").atZone(NEW_YORK).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
        repo.addSteps("counter", first, 50, NEW_YORK, 1);
        repo.addSteps("counter", second, 70, NEW_YORK, 1);
        assertEquals(120, repo.day("2026-11-01"));
        long[] h = repo.hours(LocalDate.parse("2026-11-01"), NEW_YORK);
        assertEquals(50, h[1]);
        assertEquals(70, h[2]);
    }

    @Test public void timezoneChangeBetweenSyncsDoesNotDoubleCount() {
        long from = at("2026-11-05T00:00", KOLKATA), to = from + 24 * 3_600_000L;
        long[] starts = {at("2026-11-05T10:00", KOLKATA)};
        repo.replaceRange("recording", from, to, starts, new long[]{800}, KOLKATA, 1);
        // the same Play services data read again after travelling to Kathmandu
        repo.replaceRange("recording", from, to, starts, new long[]{800}, KATHMANDU, 2);
        long total = 0;
        for (long v : repo.daysFrom("2026-11-01").values()) total += v;
        assertEquals(800, total);
    }

    @Test public void sourceSwitchMidDayTakesLargerPerBucketNeverSum() {
        long from = at("2026-11-05T00:00", KOLKATA), to = from + 24 * 3_600_000L;
        // morning from the Recording API
        repo.replaceRange("recording", from, to, new long[]{at("2026-11-05T09:00", KOLKATA), at("2026-11-05T12:00", KOLKATA)},
                new long[]{1000, 300}, KOLKATA, 1);
        // Play services went away: the phone counter takes over from 12:00 (same 12:00 bucket overlaps)
        repo.addSteps("counter", at("2026-11-05T12:05", KOLKATA), 280, KOLKATA, 2);
        repo.addSteps("counter", at("2026-11-05T15:00", KOLKATA), 500, KOLKATA, 2);
        assertEquals(1000 + 300 + 500, repo.day("2026-11-05"));      // 12:00 bucket: max(300, 280), not 580
        // Max accuracy found more steps in the 09:00 bucket: max(1000, 1150)
        repo.addSteps("boost", at("2026-11-05T09:10", KOLKATA), 1150, KOLKATA, 3);
        assertEquals(1150 + 300 + 500, repo.day("2026-11-05"));
        assertEquals("boost", repo.daySource("2026-11-05"));
    }

    @Test public void legacyHistoryMigratesWithoutDoubleCounting() {
        Context c = RuntimeEnvironment.getApplication();
        SharedPreferences p = c.getSharedPreferences("test-legacy", Context.MODE_PRIVATE);
        p.edit().putString("history", "{\"2026-11-03\": 6400, \"2026-11-04\": 9100}")
                .putString("day", "2026-11-05").putLong("steps", 2500).apply();
        repo.migrateLegacy(p);
        repo.migrateLegacy(p);                                        // runs only once
        assertEquals(6400, repo.day("2026-11-03"));
        assertEquals(9100, repo.day("2026-11-04"));
        assertEquals(2500, repo.day("2026-11-05"));
        // today the Recording API also has those same steps (and a few more): larger wins, no sum
        long from = at("2026-11-05T00:00", KOLKATA);
        repo.replaceRange("recording", from, from + 86_400_000L, new long[]{at("2026-11-05T08:00", KOLKATA)}, new long[]{2600}, KOLKATA, 5);
        assertEquals(2600, repo.day("2026-11-05"));
    }
}
