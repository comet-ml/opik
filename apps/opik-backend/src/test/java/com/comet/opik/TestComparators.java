package com.comet.opik;

import java.time.Instant;

public class TestComparators {
    public static int compareMicroNanoTime(Instant i1, Instant i2) {
        // Calculate the difference in nanoseconds
        long nanoDifference = Math.abs(i1.getNano() - i2.getNano());
        if (nanoDifference <= 1_000) {
            return 0; // Consider equal if within a microsecond
        }
        return i1.compareTo(i2);
    }
}
