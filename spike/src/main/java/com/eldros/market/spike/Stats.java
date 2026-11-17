package com.eldros.market.spike;

import java.util.Arrays;

/** Nanosecond samples to p50/p95/p99/max in milliseconds. */
final class Stats {

    private Stats() {
    }

    static String summarize(long[] nanos) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return String.format("n=%d p50=%.4fms p95=%.4fms p99=%.4fms max=%.4fms",
                s.length, ms(pick(s, 0.50)), ms(pick(s, 0.95)), ms(pick(s, 0.99)), ms(s[s.length - 1]));
    }

    private static long pick(long[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
