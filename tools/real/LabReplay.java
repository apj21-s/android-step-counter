package com.arco.stride;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lab-mode recordings through the app's own code, one row per recording, signed errors.
 * Accel is thinned to 25 Hz like the app; the whole recording is one bout.
 *   chip       - step-counter delta (what the chip alone gives)
 *   peaks      - MotionCounter peaks;  hand = peaks + rhythm (StepFusion.handEstimate)
 *   model      - StepNet while walking (Lab-mode only, not used for counting)
 *   app        - what Stride stores: StepFusion's choice, less vehicle steps
 *   app/chipH  - the same but with chip-only in a steady hand (the alternative to "motion if higher")
 *   stored     - what the database ends up with: the chip's steps as the Recording API would bucket them,
 *                StepEngine.applyBout's boost and vehicle rows, combined per 15-min bucket by Buckets.value
 *                (the same rule StepRepository uses). Needs wall0/ns0 in the header (newer files);
 *                older files are bucketed from the first sample.
 * Usage: LabReplay <lab .csv files...>
 */
public class LabReplay {
    public static void main(String[] args) throws Exception {
        System.out.println("recording                                truth   chip   peaks   hand  model  place    vehicle | app (err)          | app/chipH (err)");
        double[] sumApp = new double[2], sumAlt = new double[2];
        for (String f : args) {
            List<String> lines = Files.readAllLines(Path.of(f));
            Map<String, String> head = new TreeMap<>();
            for (String kv : lines.get(0).replace("#", "").trim().split(" ")) { String[] p = kv.split("="); if (p.length == 2) head.put(p[0], p[1]); }
            int truth = Integer.parseInt(head.get("truth"));
            List<long[]> vehicle = new ArrayList<>();          // {ns, entered}
            for (String l : lines) { String[] r = l.split(","); if (r.length > 2 && r[1].equals("V")) vehicle.add(new long[]{Long.parseLong(r[0]), (long) Float.parseFloat(r[2])}); }
            vehicle.sort((x, y) -> Long.compare(x[0], y[0]));
            long wall0 = Long.parseLong(head.getOrDefault("wall0", "0")), ns0 = Long.parseLong(head.getOrDefault("ns0", "-1"));
            double[] app = replay(lines, vehicle, false, wall0, ns0), alt = replay(lines, vehicle, true, wall0, ns0);
            String name = Path.of(f).getFileName().toString();
            System.out.printf(Locale.ROOT, "%-40s %5d %6.0f %7.0f %6.0f %6.0f  %-8s %6.0f | %6.0f (%s) | %6.0f (%s)%n", name.length() > 40 ? name.substring(0, 40) : name,
                    truth, app[1], app[2], app[3], app[4], Placement.NAMES[(int) app[5]], app[6], app[0], err(app[0], truth), alt[0], err(alt[0], truth));
            System.out.printf(Locale.ROOT, "%-40s stored total %.0f (%s) in %.0f bucket(s)%s%n", "", app[7], err(app[7], truth), app[8],
                    head.containsKey("wall0") ? "" : " (no wall0 in header: buckets from the first sample)");
            if (truth > 0) { sumApp[0] += (app[7] - truth) / truth; sumAlt[0] += (alt[7] - truth) / truth; sumApp[1]++; }
        }
        if (sumApp[1] > 0)
            System.out.printf(Locale.ROOT, "mean signed error of the stored total over walks: app %+.1f%%, chip-only in hand %+.1f%%%n", 100 * sumApp[0] / sumApp[1], 100 * sumAlt[0] / sumApp[1]);
    }

    static String err(double got, int truth) {
        return truth == 0 ? String.format(Locale.ROOT, "%+.0f fake", got) : String.format(Locale.ROOT, "%+5.1f%%", 100 * (got - truth) / truth);
    }

    /** Returns {bout total, chip, peaks, hand estimate, model, placement, vehicle steps removed, stored total, buckets}. */
    static double[] replay(List<String> lines, List<long[]> vehicle, boolean chipOnlyInHand, long wall0, long ns0) {
        TreeMap<Long, Long> chipByNs = new TreeMap<>();
        MotionCounter mc = new MotionCounter();
        Placement place = new Placement();
        StepNet net = StepNet.standard();
        StepFusion fusion = new StepFusion();
        if (!vehicle.isEmpty()) fusion.vehicleAt = ns -> {
            boolean in = false;
            for (long[] v : vehicle) { if (v[0] > ns) break; in = v[1] == 1; }
            return in;
        };
        long first = -1, lastCounter = -1, peaks = 0, rhythm = 0, t = 0, lastTick = 0;
        double model = 0;
        int accelN = 0;
        for (String l : lines) {
            if (l.startsWith("#")) continue;
            String[] r = l.split(",");
            t = Long.parseLong(r[0]);
            if (first < 0) { first = t; fusion.start(t); }
            switch (r[1]) {
                case "A": {
                    if ((accelN++ & 1) != 0) break;              // 50 Hz -> 25 Hz
                    float x = Float.parseFloat(r[2]), y = Float.parseFloat(r[3]), z = Float.parseFloat(r[4]);
                    long rb = mc.rhythmSteps;
                    int n = mc.onSample(x, y, z, t);
                    double nb = net.total;
                    net.onSample(x, y, z, 0, t);
                    if (mc.walking) model += net.total - nb;
                    peaks += n;
                    rhythm += mc.rhythmSteps - rb;
                    place.onAccel(x, y, z, t);
                    fusion.motion(n, t);
                    fusion.rhythm((int) (mc.rhythmSteps - rb), t);
                    if (t - lastTick >= 1_000_000_000L) { lastTick = t; fusion.vote(place.current(t), t); fusion.tick(t); }
                    break;
                }
                case "C": {
                    long raw = (long) Float.parseFloat(r[2]);
                    if (lastCounter >= 0 && raw > lastCounter) { fusion.chip(raw - lastCounter, t); chipByNs.merge(t, raw - lastCounter, Long::sum); }
                    lastCounter = raw;
                    break;
                }
                case "P":
                    place.onProximity(Float.parseFloat(r[2]) < 1f, t);    // most phones: 0 = near
                    break;
                default:
            }
        }
        for (int i = 0; i < 200 && fusion.active(); i++) { t += 1_000_000_000L; fusion.tick(t); }
        long removed = fusion.chipBySecond.values().stream().mapToLong(Long::longValue).sum();
        boolean motion = fusion.usedMotion && !(chipOnlyInHand && fusion.place == Placement.HAND);
        double appTotal = (motion ? Math.max(fusion.motionSteps, fusion.chipSteps) : fusion.chipSteps);
        long chipAll = fusion.chipSteps + removed;

        // what the database ends up with, bucket by bucket (same pieces and rule as the app)
        final long base = ns0 >= 0 ? ns0 : first, wallBase = wall0;
        java.util.function.LongUnaryOperator toWall = ns -> wallBase + (ns - base) / 1_000_000L;
        TreeMap<Long, Map<String, Long>> buckets = new TreeMap<>();
        TreeMap<Long, Long> minus = new TreeMap<>();
        for (Map.Entry<Long, Long> e : chipByNs.entrySet())
            buckets.computeIfAbsent(bucket(toWall.applyAsLong(e.getKey())), k -> new TreeMap<>()).merge("recording", e.getValue(), Long::sum);
        if (motion) for (Map.Entry<Long, Long> e : fusion.motionBySecond.entrySet())
            buckets.computeIfAbsent(bucket(toWall.applyAsLong(e.getKey() * 1_000_000_000L)), k -> new TreeMap<>()).merge("boost", e.getValue(), Long::sum);
        for (Map.Entry<Long, Long> e : fusion.chipBySecond.entrySet())
            minus.merge(bucket(toWall.applyAsLong(e.getKey() * 1_000_000_000L)), e.getValue(), Long::sum);
        long stored = 0;
        for (Map.Entry<Long, Map<String, Long>> b : buckets.entrySet()) stored += Buckets.value(b.getValue(), minus.getOrDefault(b.getKey(), 0L));
        return new double[]{appTotal, chipAll, peaks, StepFusion.handEstimate(peaks, rhythm), model, fusion.place, removed, stored, buckets.size()};
    }

    static long bucket(long wallMs) { return wallMs - Math.floorMod(wallMs, 15 * 60_000L); }
}
