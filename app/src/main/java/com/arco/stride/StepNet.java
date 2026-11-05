package com.arco.stride;

/**
 * Small GRU step model (trained in tools/ml/train.py on the Cambridge walks).
 * Plain Java, no libraries: ~1,000 weights, about 1,000 multiply-adds per sample at 25 Hz.
 * Output is steps per sample; summed over a walk it gives the step count.
 * Gate order and maths follow PyTorch's nn.GRU so trained weights load unchanged.
 */
final class StepNet {
    final int nin, h;
    private final float[] wih, whh, bih, bhh, wout;
    private final float bout;
    private final float[] state, gi, gh;
    private final float[] g = new float[3];
    private boolean init;
    private long lastNs;
    private double sum;           // fractional steps not handed out yet
    double total;                 // all steps since reset (fractional)

    StepNet(int nin, int h, float[] wih, float[] whh, float[] bih, float[] bhh, float[] wout, float bout) {
        this.nin = nin; this.h = h; this.wih = wih; this.whh = whh; this.bih = bih; this.bhh = bhh; this.wout = wout; this.bout = bout;
        state = new float[h]; gi = new float[3 * h]; gh = new float[3 * h];
    }

    /** The model shipped with the app (accelerometer only). */
    static StepNet standard() {
        return new StepNet(StepNetWeights.NIN, StepNetWeights.H, StepNetWeights.WIH, StepNetWeights.WHH,
                StepNetWeights.BIH, StepNetWeights.BHH, StepNetWeights.WOUT, StepNetWeights.BOUT);
    }

    /** Weights as written by train.py ("name v v v ..." per line), for tests and tools. */
    static StepNet fromText(java.util.List<String> lines) {
        java.util.Map<String, float[]> m = new java.util.HashMap<>();
        for (String line : lines) {
            String[] p = line.trim().split(" ");
            float[] v = new float[p.length - 1];
            for (int i = 1; i < p.length; i++) v[i - 1] = Float.parseFloat(p[i]);
            m.put(p[0], v);
        }
        int h = m.get("gru.bias_hh_l0").length / 3;
        int nin = m.get("gru.weight_ih_l0").length / (3 * h);
        return new StepNet(nin, h, m.get("gru.weight_ih_l0"), m.get("gru.weight_hh_l0"), m.get("gru.bias_ih_l0"),
                m.get("gru.bias_hh_l0"), m.get("out.weight"), m.get("out.bias")[0]);
    }

    void reset() { init = false; }

    /** Feed one accelerometer sample (m/s², ns), plus gyro magnitude (rad/s) if the model uses it. Returns whole steps now. */
    int onSample(float x, float y, float z, float gyroMag, long tNs) {
        if (!init || tNs - lastNs > 1_000_000_000L || tNs <= lastNs) {
            g[0] = x; g[1] = y; g[2] = z; init = true; lastNs = tNs;
            java.util.Arrays.fill(state, 0);
            sum = 0;
        } else {
            lastNs = tNs;
        }
        // same features as train.py: gravity = 0.5 Hz low-pass of each axis (fixed 25 Hz step, as in training)
        float a = 1 - (float) Math.exp(-2 * Math.PI * 0.5 * 0.04);
        g[0] += a * (x - g[0]); g[1] += a * (y - g[1]); g[2] += a * (z - g[2]);
        float gn = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) + 1e-6f;
        float ux = g[0] / gn, uy = g[1] / gn, uz = g[2] / gn;
        float dx = x - g[0], dy = y - g[1], dz = z - g[2];
        float vert = dx * ux + dy * uy + dz * uz;
        float hx = dx - vert * ux, hy = dy - vert * uy, hz = dz - vert * uz;
        float[] in = new float[nin];
        in[0] = ((float) Math.sqrt(x * x + y * y + z * z) - gn) / 3;
        in[1] = vert / 3;
        in[2] = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) / 3;
        if (nin > 3) in[3] = gyroMag / 3;
        double steps = step(in);
        total += steps;
        sum += steps;
        int whole = (int) sum;
        sum -= whole;
        return whole;
    }

    /** One GRU step; returns steps for this sample. */
    double step(float[] in) {
        int n3 = 3 * h;
        for (int r = 0; r < n3; r++) {
            float s = bih[r];
            for (int c = 0; c < nin; c++) s += wih[r * nin + c] * in[c];
            gi[r] = s;
            float t = bhh[r];
            for (int c = 0; c < h; c++) t += whh[r * h + c] * state[c];
            gh[r] = t;
        }
        for (int j = 0; j < h; j++) {
            float rg = sigmoid(gi[j] + gh[j]);
            float zg = sigmoid(gi[h + j] + gh[h + j]);
            float ng = (float) Math.tanh(gi[2 * h + j] + rg * gh[2 * h + j]);
            state[j] = (1 - zg) * ng + zg * state[j];
        }
        float o = bout;
        for (int j = 0; j < h; j++) o += wout[j] * state[j];
        double sp = o > 20 ? o : Math.log1p(Math.exp(o));     // softplus
        return sp * 0.2;
    }

    private static float sigmoid(float v) { return 1f / (1f + (float) Math.exp(-v)); }
}
