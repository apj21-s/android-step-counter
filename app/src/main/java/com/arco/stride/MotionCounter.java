package com.arco.stride;

/**
 * Step counting from the accelerometer at ~25 samples a second (any phone position).
 * Plain Java (no Android classes) so it can be tested on a computer.
 *
 * 1. Total acceleration minus gravity, smoothed below `lowHz`.
 * 2. Every 0.5 s, autocorrelation over the last 4 s finds the step period. A pocket phone
 *    often repeats once per stride (two steps) rather than once per step, so the stride lag
 *    counts as walking too, and the step period is half of it.
 * 3. Peaks: a rise above an adaptive line is a step, unless it comes sooner than
 *    `refractory` × step period after the last one (then it's a second bump inside the same
 *    step and is ignored, instead of resetting the count).
 * 4. Rhythm: while walking, steps = time ÷ step period. Used when peaks are too faint.
 * Steps seen in the 4 s before walking is confirmed are credited back.
 */
final class MotionCounter {
    static final int BUF = 128;
    private final float[] buf = new float[BUF];
    private int head, filled;
    private float gravity, smooth, env, energy, peak, sampleDt = 0.04f;
    private boolean init, above;
    private long lastNs, lastAcNs, lastRise, walkUntil;
    private final long[] rises = new long[16];
    private int riseHead;
    private long creditedUpTo;
    private double phase;
    private float candPeriod;

    // tunables (defaults chosen on half of the Cambridge walks, checked on the other half)
    float floor = 0.12f;        // smallest rise (m/s²) that can be a step; calibration may change it
    float lowHz = 2.2f;         // 1.8 scored slightly better on the walks but would damp running (~3 steps/s)
    float lineFrac = 0.45f;     // adaptive line = this × recent swing
    float refractory = 0.6f;    // ignore rises sooner than this × step period
    float onThr = 0.5f;         // autocorrelation needed to call it walking
    float minEnergy = 0.006f;
    float windowS = 4f;
    float maxStepS = 0.85f;     // a repeat slower than this is a stride (two steps), not one step
    float halfFrac = 0.3f;      // ...and so is a lag whose half also repeats this well
    float minStepS = 0.27f;     // faster than this is shaking or vibration
    float periodTol = 0.2f;     // two checks in a row must agree on the period within this share
    float riseLo = 0.4f, riseHi = 1.6f;
    float shakeFrac = 0.85f;    // a sub-step repeat this strong (vs the step repeat) means shaking
    float maxGapCv = 0.45f;     // gaps between peaks must be this even (spread ÷ mean); random bumps are ~1   // peaks in the window ÷ (window ÷ period) must fall in this range

    // state readable by fusion and tests
    boolean walking;
    float period = 0.55f;       // current step period (s)
    float lastAc;               // latest autocorrelation
    long peakSteps, rhythmSteps;

    final float[] peaks = new float[64];
    int peakCount;

    void reset() { init = false; }

    /** Feed one sample (m/s², ns). Returns peak steps confirmed now. Rhythm steps accumulate in rhythmSteps. */
    int onSample(float x, float y, float z, long tNs) {
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        if (!init || tNs - lastNs > 1_000_000_000L || tNs <= lastNs) {
            gravity = m; smooth = 0; env = 0; energy = 0; init = true; lastNs = tNs; above = false;
            filled = 0; head = 0; walking = false; candPeriod = 0; walkUntil = 0; lastRise = 0; creditedUpTo = tNs;
            for (int i = 0; i < rises.length; i++) rises[i] = 0;
            phase = 0;
            return 0;
        }
        float dt = (tNs - lastNs) / 1e9f;
        lastNs = tNs;
        sampleDt += 0.05f * (dt - sampleDt);

        gravity += (1 - (float) Math.exp(-2 * Math.PI * 0.5 * dt)) * (m - gravity);
        smooth += (1 - (float) Math.exp(-2 * Math.PI * lowHz * dt)) * ((m - gravity) - smooth);
        env = Math.max(Math.abs(smooth), env * (float) Math.exp(-dt / 1.5f));
        energy += (1 - (float) Math.exp(-dt / 1.0f)) * (smooth * smooth - energy);
        buf[head] = smooth;
        head = (head + 1) % BUF;
        if (filled < BUF) filled++;

        int steps = 0;
        if (tNs - lastAcNs > 500_000_000L) {
            lastAcNs = tNs;
            boolean was = walking;
            float prev = candPeriod;
            boolean cand = energy >= minEnergy && periodic();
            candPeriod = cand ? period : 0;
            if (cand && prev > 0 && Math.abs(period - prev) <= periodTol * prev && risesFit(tNs)) walkUntil = tNs + 1_500_000_000L;
            walking = tNs < walkUntil;
            if (walking && !was) steps += creditBack(tNs - (long) (windowS * 1e9));
        }
        if (walking) {
            phase += dt / period;
            if (phase >= 1) { int k = (int) phase; rhythmSteps += k; phase -= k; }
        } else {
            phase = 0;
        }

        float line = Math.max(floor, lineFrac * env);
        if (!above && smooth > line) {
            above = true;
            peak = smooth;
            if (lastRise == 0 || tNs - lastRise >= refractory * period * 1e9) {
                lastRise = tNs;
                rises[riseHead] = tNs;
                riseHead = (riseHead + 1) % rises.length;
                if (walking) { steps++; creditedUpTo = tNs; }
            }
        } else if (above) {
            if (smooth > peak) peak = smooth;
            if (smooth < 0) {
                above = false;
                peaks[peakCount % peaks.length] = peak;
                peakCount++;
            }
        }
        peakSteps += steps;
        return steps;
    }

    /** Rises after `fromNs` that were not counted yet (the start of a walk). */
    private int creditBack(long fromNs) {
        int n = 0;
        for (long r : rises) if (r > fromNs && r > creditedUpTo) n++;
        creditedUpTo = lastNs;
        return n;
    }

    /** Do the peaks in the window match the period? (Random bumps rarely do.) */
    private boolean risesFit(long tNs) {
        long from = tNs - (long) (windowS * 1e9);
        long[] ts = new long[rises.length];
        int n = 0;
        for (long r : rises) if (r > from) ts[n++] = r;
        float expected = windowS / period;
        if (n < riseLo * expected || n > riseHi * expected) return false;
        if (n < 4) return true;
        java.util.Arrays.sort(ts, 0, n);
        double sum = 0, sq = 0;
        for (int i = 1; i < n; i++) { double g = ts[i] - ts[i - 1]; sum += g; sq += g * g; }
        double mean = sum / (n - 1), var = Math.max(0, sq / (n - 1) - mean * mean);
        return Math.sqrt(var) / mean <= maxGapCv;
    }

    /** Autocorrelation over the last `windowS`; sets period and lastAc. */
    private boolean periodic() {
        int n = Math.min(filled, Math.round(windowS / sampleDt));
        int lo = Math.max(1, Math.round(0.25f / sampleDt));
        int hi = Math.min(n - 10, Math.round(1.4f / sampleDt));
        if (n < 30 || hi <= lo) { lastAc = 0; return false; }
        float[] v = new float[n];
        float mean = 0;
        for (int i = 0; i < n; i++) { v[i] = buf[(head - n + i + BUF) % BUF]; mean += v[i]; }
        mean /= n;
        for (int i = 0; i < n; i++) v[i] -= mean;
        float[] r = new float[hi + 2];
        int best = -1;
        for (int k = lo; k <= hi + 1 && k < n; k++) {
            float s = 0, a = 0, b = 0;
            for (int i = 0; i + k < n; i++) { s += v[i] * v[i + k]; a += v[i] * v[i]; b += v[i + k] * v[i + k]; }
            r[k] = s / (float) Math.sqrt(a * b + 1e-9f);
            if (k <= hi && (best < 0 || r[k] > r[best])) best = k;
        }
        lastAc = r[best];
        if (lastAc < onThr) return false;
        // a faster repeat than any step (shaking, engine, road) beats the step lag: not walking
        for (int k = Math.max(1, Math.round(0.1f / sampleDt)); k < lo; k++) {
            float s = 0, a = 0, b = 0;
            for (int i = 0; i + k < n; i++) { s += v[i] * v[i + k]; a += v[i] * v[i]; b += v[i + k] * v[i + k]; }
            if (s / (float) Math.sqrt(a * b + 1e-9f) > shakeFrac * lastAc) return false;
        }
        // Is the best lag one step or one stride? If half of it is also a clear repeat, it's a stride.
        int half = Math.round(best / 2f);
        float lag = best;
        if (half >= lo) {
            float rh = Math.max(r[half], Math.max(r[half - 1], r[Math.min(half + 1, hi)]));
            if (rh > halfFrac * lastAc || best * sampleDt > maxStepS) lag = best / 2f;
        }
        if (lag * sampleDt < minStepS) return false;
        period = lag * sampleDt;
        return true;
    }

    float medianPeak() {
        int n = Math.min(peakCount, peaks.length);
        if (n == 0) return 0;
        float[] copy = new float[n];
        System.arraycopy(peaks, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }
}
