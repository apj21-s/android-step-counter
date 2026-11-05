package com.arco.stride;

import java.util.Map;

/**
 * The one rule for a 15-minute bucket's value. Plain Java so tools can use the exact same rule.
 *
 * Sources: recording / counter / detector come from the phone's step chip; boost (motion steps from
 * Max accuracy bouts) and accel (motion detector on phones without a chip) don't.
 * Vehicle steps (bucket_minus) are fake CHIP steps, so they come off the chip sources only;
 * then the bucket is the largest source, never below zero.
 */
final class Buckets {
    private Buckets() {}

    static boolean fromChip(String source) { return !source.equals("boost") && !source.equals("accel"); }

    static long value(Map<String, Long> sources, long minus) {
        long best = 0;
        for (Map.Entry<String, Long> e : sources.entrySet()) {
            long v = fromChip(e.getKey()) ? e.getValue() - minus : e.getValue();
            if (v > best) best = v;
        }
        return best;
    }

    /** Source that gives the bucket its value (for the debug/"source" label), or null if the bucket is 0. */
    static String winner(Map<String, Long> sources, long minus) {
        String w = null;
        long best = 0;
        for (Map.Entry<String, Long> e : sources.entrySet()) {
            long v = fromChip(e.getKey()) ? e.getValue() - minus : e.getValue();
            if (v > best || (v == best && w != null && e.getKey().compareTo(w) < 0)) { best = v; w = e.getKey(); }
        }
        return best > 0 ? w : null;
    }
}
