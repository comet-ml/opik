package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;

/**
 * Weighted k-means on unit vectors, dependency-free: k-means++ initialisation (weighted D² sampling, one candidate per
 * step), weighted Lloyd until no label changes or {@value #MAX_ITERATIONS} iterations, {@value #RESTARTS} restarts from
 * one seeded {@link SplittableRandom}, the restart with the lowest weighted inertia kept. Centres are L2-normalized after
 * the fit. A cluster left empty by an update takes the point farthest from its centre, so none stays empty.
 *
 * <p>This is not scikit-learn's k-means (whose ++ step tries several candidates greedily); stage 0 measured that the two
 * differ by about as much as scikit-learn differs from itself with another seed. Deterministic for a given input and
 * seed. Checks the thread's interrupt flag once per iteration, so a run past its time bound stops.
 */
@UtilityClass
public class KMeans {

    static final int MAX_ITERATIONS = 300;
    static final int RESTARTS = 3;

    /**
     * @param points  {@code n * dimensions} values
     * @param weights per point, positive
     */
    public static Result fit(@NonNull float[] points, @NonNull double[] weights, int dimensions, int k, long seed) {
        int n = weights.length;
        Preconditions.checkArgument(points.length == n * dimensions,
                "points must hold one row per weight, weights '%s', values '%s'", n, points.length);
        Preconditions.checkArgument(k >= 1 && k <= n, "k must be in [1, n], k '%s', n '%s'", k, n);
        var random = new SplittableRandom(seed);
        Fit best = null;
        for (int restart = 0; restart < RESTARTS; restart++) {
            var fit = lloyd(points, weights, n, dimensions, k, plusPlus(points, weights, n, dimensions, k, random));
            if (best == null || fit.inertia() < best.inertia()) {
                best = fit;
            }
        }
        return Result.builder()
                .labels(best.labels())
                .centres(normalized(best.centres(), k, dimensions))
                .inertia(best.inertia())
                .iterations(best.iterations())
                .build();
    }

    private static double[] plusPlus(float[] x, double[] w, int n, int d, int k, SplittableRandom random) {
        var centres = new double[k * d];
        var minDistance = new double[n];
        int first = sample(w, random);
        copyPoint(x, first, centres, 0, d);
        for (int i = 0; i < n; i++) {
            minDistance[i] = squaredDistance(x, i, centres, 0, d) * w[i];
        }
        for (int j = 1; j < k; j++) {
            checkInterrupted();
            int pick = sample(minDistance, random);
            copyPoint(x, pick, centres, j, d);
            for (int i = 0; i < n; i++) {
                minDistance[i] = Math.min(minDistance[i], squaredDistance(x, i, centres, j, d) * w[i]);
            }
        }
        return centres;
    }

    /** An index drawn with probability proportional to {@code p}; a zero entry is never drawn unless all are zero. */
    private static int sample(double[] p, SplittableRandom random) {
        double total = 0;
        for (double v : p) {
            total += v;
        }
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < p.length; i++) {
            acc += p[i];
            if (acc > r) {
                return i;
            }
        }
        return p.length - 1;
    }

    private static Fit lloyd(float[] x, double[] w, int n, int d, int k, double[] centres) {
        var labels = new int[n];
        var distance = new double[n];
        int iterations = 0;
        for (; iterations < MAX_ITERATIONS; iterations++) {
            checkInterrupted();
            boolean changed = assign(x, centres, n, d, k, labels, distance);
            if (!changed && iterations > 0) {
                break;
            }
            update(x, w, n, d, k, labels, distance, centres);
        }
        double inertia = 0;
        for (int i = 0; i < n; i++) {
            inertia += w[i] * distance[i];
        }
        return new Fit(labels, centres, inertia, iterations);
    }

    private static boolean assign(float[] x, double[] centres, int n, int d, int k, int[] labels, double[] distance) {
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            int bestJ = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int j = 0; j < k; j++) {
                double s = squaredDistance(x, i, centres, j, d);
                if (s < bestDistance) {
                    bestDistance = s;
                    bestJ = j;
                }
            }
            if (labels[i] != bestJ) {
                labels[i] = bestJ;
                changed = true;
            }
            distance[i] = bestDistance;
        }
        return changed;
    }

    /**
     * Weighted means of the clusters. An empty cluster moves to the point with the largest weighted distance to its own
     * centre that no other empty cluster took this round; the next assignment then gives it that point.
     */
    private static void update(float[] x, double[] w, int n, int d, int k, int[] labels, double[] distance,
            double[] centres) {
        var sum = new double[k * d];
        var weightSum = new double[k];
        for (int i = 0; i < n; i++) {
            int j = labels[i];
            weightSum[j] += w[i];
            for (int t = 0; t < d; t++) {
                sum[j * d + t] += w[i] * x[i * d + t];
            }
        }
        var taken = new boolean[n];
        for (int j = 0; j < k; j++) {
            if (weightSum[j] > 0) {
                for (int t = 0; t < d; t++) {
                    centres[j * d + t] = sum[j * d + t] / weightSum[j];
                }
                continue;
            }
            int far = -1;
            for (int i = 0; i < n; i++) {
                if (!taken[i] && (far < 0 || w[i] * distance[i] > w[far] * distance[far])) {
                    far = i;
                }
            }
            taken[far] = true;
            copyPoint(x, far, centres, j, d);
        }
    }

    private static double squaredDistance(float[] x, int i, double[] centres, int j, int d) {
        double s = 0;
        int xi = i * d;
        int cj = j * d;
        for (int t = 0; t < d; t++) {
            double v = x[xi + t] - centres[cj + t];
            s += v * v;
        }
        return s;
    }

    private static void copyPoint(float[] x, int i, double[] centres, int j, int d) {
        for (int t = 0; t < d; t++) {
            centres[j * d + t] = x[i * d + t];
        }
    }

    private static float[] normalized(double[] centres, int k, int d) {
        var out = new float[k * d];
        for (int j = 0; j < k; j++) {
            double norm = 0;
            for (int t = 0; t < d; t++) {
                norm += centres[j * d + t] * centres[j * d + t];
            }
            norm = Math.sqrt(norm);
            for (int t = 0; t < d; t++) {
                out[j * d + t] = (float) (norm > 0 ? centres[j * d + t] / norm : centres[j * d + t]);
            }
        }
        return out;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("k-means interrupted");
        }
    }

    /** {@code centres} are {@code k * dimensions} unit-length float32 values; {@code inertia} is the weighted one. */
    @Builder
    public record Result(@NonNull int[] labels, @NonNull float[] centres, double inertia, int iterations) {
    }

    private record Fit(int[] labels, double[] centres, double inertia, int iterations) {
    }
}
