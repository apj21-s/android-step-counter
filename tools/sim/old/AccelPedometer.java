package com.arco.stride;

/**
 * Step detection from the accelerometer, tuned for 25 samples per second.
 * Plain Java (no Android classes) so it can be tested on a computer.
 *
 * 1. Total acceleration (works in any phone orientation), band-passed to 0.5–3 Hz.
 * 2. Cheap energy gate: if the phone is nearly still, skip everything else.
 * 3. A step is the bounce rising above an adaptive line (45% of the recent swing,
 *    never below `floor`) after it dropped below zero.
 * 4. Walking gate: steps 0.25–1.25 s apart (0.8–4 steps a second), evenly spaced,
 *    and the last 2.5 s repeating (autocorrelation). Counting starts after 6 such
 *    steps; those first steps are credited back once walking is confirmed.
 */
final class AccelPedometer {
    private static final int BUF = 128;              // ~5 s at 25 Hz
    private final float[] buf = new float[BUF];
    private int head, filled;
    private float gravity, smooth, env, energy, peak, meanDt = 0.55f, sampleDt = 0.04f;
    private boolean init, above, periodic;
    private long lastNs, lastStep, lastAcNs, periodicUntil;
    private int regular, uncounted, fast;

    float floor = 0.12f;      // smallest rise (m/s²) that can be a step; calibration may change it
    float lowHz = 3.0f;       // smoothing cutoff (4 Hz let noise through at 25 Hz in the simulations)
    float minPeriodic = 0.70f;// autocorrelation needed to call it walking
    float minEnergy = 0.006f; // below this the phone is "still"
    int minSteps = 6;         // even steps needed before counting (4 counted noise as steps)
    float lastAc;             // latest autocorrelation, for tests and the debug line

    // heights of recent step peaks, used by calibration
    final float[] peaks = new float[64];
    int peakCount;

    void reset() { init = false; }

    /** Feed one sample (m/s², timestamp in ns). Returns steps confirmed now (usually 0 or 1). */
    int onSample(float x, float y, float z, long tNs) {
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        if (!init || tNs - lastNs > 1_000_000_000L || tNs <= lastNs) {   // first sample, or a gap: start over
            gravity = m; smooth = 0; env = 0; energy = 0; init = true; lastNs = tNs; above = false;
            regular = 0; uncounted = 0; fast = 0; lastStep = 0; filled = 0; head = 0;
            periodic = false; periodicUntil = 0;
            return 0;
        }
        float dt = (tNs - lastNs) / 1e9f;
        lastNs = tNs;
        sampleDt += 0.05f * (dt - sampleDt);

        gravity += (1 - (float) Math.exp(-2 * Math.PI * 0.5 * dt)) * (m - gravity);           // below 0.5 Hz: gravity, tilting
        smooth += (1 - (float) Math.exp(-2 * Math.PI * lowHz * dt)) * ((m - gravity) - smooth);  // above 4 Hz: jitter
        env = Math.max(Math.abs(smooth), env * (float) Math.exp(-dt / 1.5f));
        energy += (1 - (float) Math.exp(-dt / 1.0f)) * (smooth * smooth - energy);

        buf[head] = smooth;
        head = (head + 1) % BUF;
        if (filled < BUF) filled++;

        if (tNs - lastAcNs > 500_000_000L) {                 // twice a second
            lastAcNs = tNs;
            if (energy < minEnergy) {                           // nearly still: skip the autocorrelation
                lastAc = 0;
                periodic = false;
            } else {
                lastAc = autocorr();
                if (lastAc >= minPeriodic) { periodic = true; periodicUntil = tNs + 2_000_000_000L; }
                else if (tNs > periodicUntil) periodic = false;
            }
        }

        float line = Math.max(floor, 0.45f * env);
        if (!above && smooth > line) {
            above = true;
            peak = smooth;
            double gap = lastStep == 0 ? -1 : (tNs - lastStep) / 1e9;
            if (gap >= 0 && gap < 0.25) {
                // faster than 4 steps a second: a wobble inside one step, or shaking
                fast++;
                if (fast > regular / 3 + 1) { regular = 0; uncounted = 0; fast = 0; }
            } else {
                boolean even = gap >= 0.25 && gap <= 1.25 && (regular < 2 || Math.abs(gap - meanDt) < 0.35 * meanDt);
                if (even) {
                    regular++;
                    uncounted++;
                    meanDt = regular <= 2 ? (float) gap : 0.75f * meanDt + 0.25f * (float) gap;
                } else {
                    regular = 1;
                    uncounted = 1;
                    fast = 0;
                }
                lastStep = tNs;
            }
        } else if (above) {
            if (smooth > peak) peak = smooth;
            if (smooth < 0) {
                above = false;
                peaks[peakCount % peaks.length] = peak;
                peakCount++;
            }
        }

        int steps = 0;
        if (regular >= minSteps && periodic && uncounted > 0) { steps = uncounted; uncounted = 0; }
        if (lastStep != 0 && tNs - lastStep > 1_600_000_000L) { regular = 0; uncounted = 0; fast = 0; }
        return steps;
    }

    /** Middle value of the recent step peaks (0 if none yet). */
    float medianPeak() {
        int n = Math.min(peakCount, peaks.length);
        if (n == 0) return 0;
        float[] copy = new float[n];
        System.arraycopy(peaks, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }

    /** Best normalised autocorrelation over the last ~2.5 s, at lags of 0.3–1.3 s (one step or one stride). */
    private float autocorr() {
        int n = Math.min(filled, Math.round(2.5f / sampleDt));
        int lo = Math.max(1, Math.round(0.3f / sampleDt));
        int hi = Math.min(n - 5, Math.round(1.3f / sampleDt));
        if (n < 20 || hi <= lo) return 0;
        float[] v = new float[n];
        float mean = 0;
        for (int i = 0; i < n; i++) {
            v[i] = buf[(head - n + i + BUF) % BUF];
            mean += v[i];
        }
        mean /= n;
        for (int i = 0; i < n; i++) v[i] -= mean;
        float best = 0;
        for (int k = lo; k <= hi; k++) {
            float s = 0, a = 0, b = 0;
            for (int i = 0; i + k < n; i++) {
                s += v[i] * v[i + k];
                a += v[i] * v[i];
                b += v[i + k] * v[i + k];
            }
            float r = s / (float) Math.sqrt(a * b + 1e-9f);
            if (r > best) best = r;
        }
        return best;
    }
}
