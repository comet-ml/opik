package com.comet.opik.domain.topics;

import lombok.experimental.UtilityClass;

/**
 * The dot product the topic algorithm uses everywhere: summed in double as eight partial sums over interleaved
 * dimensions, so the additions do not wait on each other, then added together in a fixed order. Java never reorders
 * double additions, so the result is the same on every machine.
 */
@UtilityClass
class VectorMath {

    /** The row at {@code aOffset} of {@code a} dotted with the row at {@code bOffset} of {@code b}. */
    static double dot(float[] a, int aOffset, float[] b, int bOffset, int dimensions) {
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        double s3 = 0;
        double s4 = 0;
        double s5 = 0;
        double s6 = 0;
        double s7 = 0;
        int t = 0;
        for (; t + 7 < dimensions; t += 8) {
            s0 += (double) a[aOffset + t] * b[bOffset + t];
            s1 += (double) a[aOffset + t + 1] * b[bOffset + t + 1];
            s2 += (double) a[aOffset + t + 2] * b[bOffset + t + 2];
            s3 += (double) a[aOffset + t + 3] * b[bOffset + t + 3];
            s4 += (double) a[aOffset + t + 4] * b[bOffset + t + 4];
            s5 += (double) a[aOffset + t + 5] * b[bOffset + t + 5];
            s6 += (double) a[aOffset + t + 6] * b[bOffset + t + 6];
            s7 += (double) a[aOffset + t + 7] * b[bOffset + t + 7];
        }
        for (; t < dimensions; t++) {
            s0 += (double) a[aOffset + t] * b[bOffset + t];
        }
        return ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
    }

    /** The same with double rows in {@code b}, as k-means' centres are. */
    static double dot(float[] a, int aOffset, double[] b, int bOffset, int dimensions) {
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        double s3 = 0;
        double s4 = 0;
        double s5 = 0;
        double s6 = 0;
        double s7 = 0;
        int t = 0;
        for (; t + 7 < dimensions; t += 8) {
            s0 += a[aOffset + t] * b[bOffset + t];
            s1 += a[aOffset + t + 1] * b[bOffset + t + 1];
            s2 += a[aOffset + t + 2] * b[bOffset + t + 2];
            s3 += a[aOffset + t + 3] * b[bOffset + t + 3];
            s4 += a[aOffset + t + 4] * b[bOffset + t + 4];
            s5 += a[aOffset + t + 5] * b[bOffset + t + 5];
            s6 += a[aOffset + t + 6] * b[bOffset + t + 6];
            s7 += a[aOffset + t + 7] * b[bOffset + t + 7];
        }
        for (; t < dimensions; t++) {
            s0 += a[aOffset + t] * b[bOffset + t];
        }
        return ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
    }
}
