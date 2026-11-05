package com.arco.stride;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Step model vs peak counter on real walks. Each person is scored by the fold model that did not
 * see them in training (fold = person id mod 3). Usage: NetCheck <data dir> <weights prefix>
 */
public class NetCheck {
    public static void main(String[] a) throws Exception {
        List<RealSuite.Trace> all = RealSuite.load(a[0]);
        StepNet[] folds = new StepNet[3];
        for (int f = 0; f < 3; f++) {
            Path w = Path.of(a[1] + ".fold" + f + ".txt");
            if (Files.exists(w)) folds[f] = StepNet.fromText(Files.readAllLines(w));
        }
        String[] names = {"peaks", "peaks+rhythm", "model", "model (walking only)", "mean(model, peaks)"};
        double[][] byG = new double[5][names.length];
        int[] cnt = new int[5];
        double[] bias = new double[5];
        String[] groups = {"hand", "typing", "pocket", "bag"};
        double extraNet = 0, extraGated = 0;
        for (RealSuite.Trace tr : all) {
            int fold = Integer.parseInt(tr.uid) % 3;
            StepNet net = folds[fold];
            if (net == null) continue;              // that fold's model isn't trained yet
            net.reset();
            net.total = 0;
            MotionCounter mc = new MotionCounter(), mr = new MotionCounter();
            double peaks = 0, netIn = 0, gated = 0, outNet = 0, outGated = 0;
            long r0 = 0;
            for (int i = 0; i < tr.t.length; i++) {
                float[] v = tr.a[i], g = tr.g[i];
                int p = mc.onSample(v[0], v[1], v[2], tr.t[i]);
                double before = net.total;
                net.onSample(v[0], v[1], v[2], (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]), tr.t[i]);
                double d = net.total - before;
                double s = tr.t[i] / 1e9;
                boolean in = s >= tr.start && s <= tr.end + 2;
                if (in) { peaks += p; netIn += d; if (mc.walking) gated += d; }
                else { outNet += d; if (mc.walking) outGated += d; }
            }
            double rhythm = RealSuite.runCounter(tr, mr, true)[0];
            double pr = peaks < 0.9 * rhythm ? rhythm : (peaks + rhythm) / 2;
            double[] est = {peaks, pr, netIn, gated, (gated + peaks) / 2};
            String grp = RealSuite.group(tr.placement);
            int gi = java.util.Arrays.asList(groups).indexOf(grp);
            for (int k = 0; k < est.length; k++) { double e = Math.abs(est[k] - tr.steps) / tr.steps; byG[gi][k] += e; byG[4][k] += e; }
            cnt[gi]++; cnt[4]++;
            bias[gi] += (netIn - tr.steps) / tr.steps;
            extraNet += outNet; extraGated += outGated;
        }
        for (int gi = 0; gi < 5; gi++) {
            if (cnt[gi] == 0) continue;
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%-7s n=%3d", gi < 4 ? groups[gi] : "ALL", cnt[gi]));
            for (int k = 0; k < names.length; k++) sb.append(String.format(Locale.ROOT, " | %s %4.1f%%", names[k], 100 * byG[gi][k] / cnt[gi]));
            System.out.println(sb + String.format(Locale.ROOT, " | model bias %+.1f%%", 100 * bias[gi] / cnt[gi]));
        }
        System.out.printf(Locale.ROOT, "steps outside the labelled walks, per trace: model %.1f, model while walking %.1f%n", extraNet / all.size(), extraGated / all.size());
    }
}
