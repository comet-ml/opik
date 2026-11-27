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

    /**
     * The same with double rows in both: {@code a} and {@code b} widened from float rows give {@link #dot} of those
     * rows bit for bit, as widening a float to double is exact and the products and their order are the same.
     */
    static double dot(double[] a, int aOffset, double[] b, int bOffset, int dimensions) {
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

    /**
     * The row at {@code aOffset} of {@code a} dotted with the rows at {@code bOffset0} and {@code bOffset1} of {@code b},
     * into {@code out[0]} and {@code out[1]}: each sum in {@link #dot}'s order, so each equals {@code dot} bit for bit,
     * while every value of {@code a} is read once for both.
     */
    static void dot2(double[] a, int aOffset, double[] b, int bOffset0, int bOffset1, int dimensions, double[] out) {
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        double s3 = 0;
        double s4 = 0;
        double s5 = 0;
        double s6 = 0;
        double s7 = 0;
        double r0 = 0;
        double r1 = 0;
        double r2 = 0;
        double r3 = 0;
        double r4 = 0;
        double r5 = 0;
        double r6 = 0;
        double r7 = 0;
        int t = 0;
        for (; t + 7 < dimensions; t += 8) {
            double a0 = a[aOffset + t];
            double a1 = a[aOffset + t + 1];
            double a2 = a[aOffset + t + 2];
            double a3 = a[aOffset + t + 3];
            double a4 = a[aOffset + t + 4];
            double a5 = a[aOffset + t + 5];
            double a6 = a[aOffset + t + 6];
            double a7 = a[aOffset + t + 7];
            s0 += a0 * b[bOffset0 + t];
            s1 += a1 * b[bOffset0 + t + 1];
            s2 += a2 * b[bOffset0 + t + 2];
            s3 += a3 * b[bOffset0 + t + 3];
            s4 += a4 * b[bOffset0 + t + 4];
            s5 += a5 * b[bOffset0 + t + 5];
            s6 += a6 * b[bOffset0 + t + 6];
            s7 += a7 * b[bOffset0 + t + 7];
            r0 += a0 * b[bOffset1 + t];
            r1 += a1 * b[bOffset1 + t + 1];
            r2 += a2 * b[bOffset1 + t + 2];
            r3 += a3 * b[bOffset1 + t + 3];
            r4 += a4 * b[bOffset1 + t + 4];
            r5 += a5 * b[bOffset1 + t + 5];
            r6 += a6 * b[bOffset1 + t + 6];
            r7 += a7 * b[bOffset1 + t + 7];
        }
        for (; t < dimensions; t++) {
            double at = a[aOffset + t];
            s0 += at * b[bOffset0 + t];
            r0 += at * b[bOffset1 + t];
        }
        out[0] = ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
        out[1] = ((r0 + r1) + (r2 + r3)) + ((r4 + r5) + (r6 + r7));
    }
}
