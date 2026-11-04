package com.arco.stride;

/**
 * Step detection from the accelerometer (plain Java, so it can be tested off the phone).
 *
 * 1. Use total acceleration (works in any phone orientation) and remove gravity.
 * 2. Smooth the bounce. A step is when the bounce rises above an adaptive line
 *    (45% of the recent swing) after having dropped below zero, which ignores small
 *    noise wiggles inside one step.
 * 3. Walking gate: steps must be evenly spaced at a walking/running pace, the last
 *    2.5 s must repeat (autocorrelation), and 6 steps must line up before anything
 *    counts. Steps held back while deciding are added once walking is confirmed.
 */
final class AccelPedometer {
    private static final int BUF = 256;
    private final float[] buf = new float[BUF];
    private int head, filled;
    private float gravity, smooth, env, meanDt = 0.55f, sampleDt = 0.02f;
    private boolean init, above, periodic;
    private long lastNs, lastStep, lastAcNs, periodicUntil;
    private int regular, uncounted, fast;   // even steps in the chain / not yet counted / too-fast rises
    float lastAc;                            // for tests and tuning

    void reset() { init = false; }

    /** Feed one sample (m/s², timestamp in ns). Returns steps found now (usually 0 or 1). */
    int onSample(float x, float y, float z, long tNs) {
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        if (!init || tNs - lastNs > 1_000_000_000L || tNs <= lastNs) {   // first sample or a gap: start over
            gravity = m; smooth = 0; env = 0; init = true; lastNs = tNs; above = false;
            regular = uncounted = fast = 0; lastStep = 0; filled = head = 0; periodic = false; periodicUntil = 0;
            return 0;
        }
        float dt = (tNs - lastNs) / 1e9f;
        lastNs = tNs;
        sampleDt += 0.05f * (dt - sampleDt);
        gravity += (1 - (float) Math.exp(-2 * Math.PI * 0.2 * dt)) * (m - gravity);          // ~0.2 Hz: gravity
        smooth += (1 - (float) Math.exp(-2 * Math.PI * 3.0 * dt)) * ((m - gravity) - smooth); // ~3 Hz: step bounce
        env = Math.max(Math.abs(smooth), env * (float) Math.exp(-dt / 1.5f));                  // recent swing size

        buf[head] = smooth; head = (head + 1) % BUF; if (filled < BUF) filled++;
        if (tNs - lastAcNs > 500_000_000L) {                 // twice a second: is this motion repeating?
            lastAcNs = tNs;
            lastAc = autocorr();
            if (lastAc >= 0.45f) { periodic = true; periodicUntil = tNs + 2_000_000_000L; }
            else if (tNs > periodicUntil) periodic = false;
        }

        float hi = Math.max(0.12f, 0.45f * env);
        if (!above && smooth > hi) {
            above = true;
            double gap = lastStep == 0 ? -1 : (tNs - lastStep) / 1e9;
            if (gap >= 0 && gap < 0.25) {
                // Faster than anyone walks: a wobble inside a step, or shaking. Too many = not walking.
                if (++fast > regular / 3 + 1) { regular = uncounted = fast = 0; }
            } else {
                boolean even = gap >= 0.25 && gap <= 1.3 && (regular < 2 || Math.abs(gap - meanDt) < 0.35 * meanDt);
                if (even) {
                    regular++; uncounted++;
                    meanDt = regular <= 2 ? (float) gap : 0.75f * meanDt + 0.25f * (float) gap;
                } else {
                    regular = 1; uncounted = 1; fast = 0;
                }
                lastStep = tNs;
            }
        } else if (above && smooth < 0) {
            above = false;
        }

        int steps = 0;
        if (regular >= 6 && periodic && uncounted > 0) { steps = uncounted; uncounted = 0; }
        if (lastStep != 0 && (tNs - lastStep) / 1e9 > 1.6) { regular = uncounted = fast = 0; }   // stopped walking
        return steps;
    }

    /** Best normalised autocorrelation over the last ~2.5 s, at lags of 0.3–1.3 s. */
    private float autocorr() {
        int n = Math.min(filled, Math.round(2.5f / sampleDt));
        int lo = Math.max(1, Math.round(0.3f / sampleDt)), hi = Math.min(n - 10, Math.round(1.3f / sampleDt));
        if (n < 40 || hi <= lo) return 0;
        float[] v = new float[n];
        float mean = 0;
        for (int i = 0; i < n; i++) { v[i] = buf[(head - n + i + BUF) % BUF]; mean += v[i]; }
        mean /= n;
        float var = 0;
        for (int i = 0; i < n; i++) { v[i] -= mean; var += v[i] * v[i]; }
        if (var / n < 0.004f) return 0;                    // too still to be walking
        float best = 0;
        for (int k = lo; k <= hi; k++) {
            float s = 0, a = 0, b = 0;
            for (int i = 0; i + k < n; i++) { s += v[i] * v[i + k]; a += v[i] * v[i]; b += v[i + k] * v[i + k]; }
            float r = s / (float) Math.sqrt(a * b + 1e-9f);
            if (r > best) best = r;
        }
        return best;
    }
}
