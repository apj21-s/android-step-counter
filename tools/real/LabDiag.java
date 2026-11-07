package com.arco.stride;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** One lab recording, every 2 s: walking?, step period, autocorrelation, steps so far, swing angle, position. */
public class LabDiag {
    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(a[0]));
        System.out.println(lines.get(0));
        MotionCounter mc = new MotionCounter();
        Placement pl = new Placement();
        long first = -1, lastAccel = 0, lastPrint = 0, chip0 = -1, chip = 0;
        int peaks = 0;
        for (String l : lines) {
            if (l.startsWith("#")) continue;
            String[] r = l.split(",");
            long t = Long.parseLong(r[0]);
            if (r[1].equals("C")) { long v = (long) Float.parseFloat(r[2]); if (chip0 < 0) chip0 = v; chip = v - chip0; }
            if (!r[1].equals("A")) continue;
            if (first < 0) first = t;
            if (t - lastAccel < 38_000_000L && t > lastAccel) continue;
            lastAccel = t;
            float x = Float.parseFloat(r[2]), y = Float.parseFloat(r[3]), z = Float.parseFloat(r[4]);
            peaks += mc.onSample(x, y, z, t);
            pl.onAccel(x, y, z, t);
            if (t - lastPrint >= 2_000_000_000L) {
                lastPrint = t;
                System.out.printf(Locale.ROOT, "%5.1f s  walking %-5s period %.2f s  ac %.2f  peaks %3d  rhythm %3d  chip %3d  swing %.2f rad  -> %s%n",
                        (t - first) / 1e9, mc.walking, mc.period, mc.lastAc, peaks, mc.rhythmSteps, chip, pl.swingRad(), Placement.NAMES[pl.current(t)]);
            }
        }
    }
}
