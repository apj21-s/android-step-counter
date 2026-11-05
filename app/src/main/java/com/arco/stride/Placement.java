package com.arco.stride;

/**
 * Where the phone is while walking. Plain Java (no Android classes) so it can be tested on a computer.
 *
 *   COVERED - proximity says near: pocket or bag. (Darkness alone isn't used: walking at night
 *             with the phone in hand is dark too. Lab mode records light for tuning later.)
 *   SWING   - the phone's direction swings a lot with each step: swinging hand (or a loose pocket).
 *   HAND    - screen facing up/towards the face and fairly steady: held, reading or typing.
 *   UPRIGHT - none of those, phone upright or on its side: usually a pocket or bag.
 *
 * Checked on the Cambridge walks (no proximity/light there): the screen-up rule finds 94% of
 * held-phone windows and calls 1% of pocket/bag windows "hand". Handbags (phone lying flat) need
 * the proximity check. The swing threshold is not checked on real data yet (no swinging-arm walks).
 */
final class Placement {
    static final int UNKNOWN = 0, HAND = 1, COVERED = 2, SWING = 3, UPRIGHT = 4;
    static final String[] NAMES = {"unknown", "hand", "covered", "swing", "upright"};

    float screenUp = 0.6f;      // |gravity on z| share needed to call it "screen up"
    float swingRad = 0.35f;     // average tilt away from the mean direction that means swinging

    private final float[] g = new float[3], f = new float[3];
    private float swing;
    private boolean init;
    private long lastNs;
    private boolean near;                // proximity reports only on change: keep the last state
    private final int[] votes = new int[5];
    private long lastVoteNs;

    void onProximity(boolean isNear, long tNs) { near = isNear; }


    void onAccel(float x, float y, float z, long tNs) {
        if (!init || tNs - lastNs > 1_000_000_000L) {
            g[0] = f[0] = x; g[1] = f[1] = y; g[2] = f[2] = z; swing = 0; init = true; lastNs = tNs;
            return;
        }
        float dt = (tNs - lastNs) / 1e9f;
        lastNs = tNs;
        float ag = 1 - (float) Math.exp(-2 * Math.PI * 0.3 * dt), af = 1 - (float) Math.exp(-2 * Math.PI * 3 * dt);
        g[0] += ag * (x - g[0]); g[1] += ag * (y - g[1]); g[2] += ag * (z - g[2]);
        f[0] += af * (x - f[0]); f[1] += af * (y - f[1]); f[2] += af * (z - f[2]);
        float ng = norm(g), nf = norm(f);
        if (ng > 1 && nf > 1) {
            float cos = (g[0] * f[0] + g[1] * f[1] + g[2] * f[2]) / (ng * nf);
            float ang = (float) Math.acos(Math.max(-1, Math.min(1, cos)));
            swing += (1 - (float) Math.exp(-dt / 2f)) * (ang - swing);
        }
        if (tNs - lastVoteNs >= 1_000_000_000L) { lastVoteNs = tNs; votes[current(tNs)]++; }
    }

    int current(long tNs) {
        if (!init) return UNKNOWN;
        if (near) return COVERED;
        if (swing > swingRad) return SWING;
        float ng = norm(g);
        if (ng < 1) return UNKNOWN;
        return Math.abs(g[2]) / ng >= screenUp ? HAND : UPRIGHT;
    }

    /** Most common position since the last reset (one vote a second). */
    int boutVote() {
        int best = UNKNOWN;
        for (int i = 1; i < votes.length; i++) if (votes[i] > votes[best]) best = i;
        return votes[best] == 0 ? UNKNOWN : best;
    }

    /** Share of votes for `p` since the last reset. */
    float share(int p) {
        int n = 0;
        for (int v : votes) n += v;
        return n == 0 ? 0 : votes[p] / (float) n;
    }

    void resetVotes() { java.util.Arrays.fill(votes, 0); }

    float swingRad() { return swing; }

    private static float norm(float[] v) { return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }
}
