package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;

/**
 * Weighted k-means on unit vectors, dependency-free: k-means++ initialisation (weighted D² sampling, one candidate per
 * step), weighted Lloyd until no label changes or {@code maxIterations} iterations, {@code restarts} restarts from
 * one seeded {@link SplittableRandom}, the restart with the lowest weighted inertia kept. Centres are L2-normalized after
 * the fit. A cluster left empty by an update takes the point farthest from its centre, so none stays empty.
 *
 * <p>Lloyd keeps Elkan's bounds, so a point is measured again only against the centres those bounds do not rule out;
 * the labels are the ones plain Lloyd gives. A squared distance is |x|² + |c|² - 2 x·c, the dot product summed in
 * double as eight partial sums over interleaved dimensions, added together in a fixed order. Java never reorders
 * double additions, so the sums run in the same order on every machine. Only an empty cluster's new point is picked
 * with the direct sum of {@code (x - c)²}, as plain Lloyd does. The bounds take {@code n * k} doubles and are
 * allocated once per fit, as are the other per-point buffers; only the labels are new per restart, since the best
 * restart keeps its own.
 *
 * <p>This is not scikit-learn's k-means (whose ++ step tries several candidates greedily); a measurement showed that the two
 * differ by about as much as scikit-learn differs from itself with another seed. Deterministic for a given input and
 * seed. Checks the thread's interrupt flag once per iteration, so a run past its time bound stops.
 */
@UtilityClass
public class KMeans {

    /**
     * @param points        {@code n * dimensions} values
     * @param weights       per point, positive
     * @param maxIterations the Lloyd iterations one restart may take; a fit that reaches it stops there
     * @param restarts      the k-means++ starts; the one with the lowest weighted inertia is kept
     */
    public static KMeansResult fit(@NonNull float[] points, @NonNull double[] weights, int dimensions, int k, long seed,
            int maxIterations, int restarts) {
        int n = weights.length;
        Preconditions.checkArgument(dimensions > 0, "dimensions must be positive, dimensions '%s'", dimensions);
        Preconditions.checkArgument(maxIterations > 0, "maxIterations must be positive, maxIterations '%s'",
                maxIterations);
        Preconditions.checkArgument(restarts > 0, "restarts must be positive, restarts '%s'", restarts);
        Preconditions.checkArgument(points.length == n * dimensions,
                "points must hold one row per weight, weights '%s', values '%s'", n, points.length);
        Preconditions.checkArgument(k >= 1 && k <= n, "k must be in [1, n], k '%s', n '%s'", k, n);
        for (double weight : weights) {
            Preconditions.checkArgument(Double.isFinite(weight) && weight > 0,
                    "weights must be finite and positive, weight '%s'", weight);
        }
        var space = new Space(points, n, dimensions, k);
        var random = new SplittableRandom(seed);
        var buffers = new Buffers(n, k, dimensions);
        Fit best = null;
        for (int restart = 0; restart < restarts; restart++) {
            var fit = lloyd(space, weights, plusPlus(space, weights, random), buffers, maxIterations);
            if (best == null || fit.inertia() < best.inertia()) {
                best = fit;
            }
        }
        return KMeansResult.builder()
                .labels(best.labels())
                .centres(normalized(best.centres(), k, dimensions))
                .inertia(best.inertia())
                .iterations(best.iterations())
                .build();
    }

    private static double[] plusPlus(Space space, double[] w, SplittableRandom random) {
        int n = space.n;
        int d = space.d;
        var centres = new double[space.k * d];
        var minDistance = new double[n];
        int first = sample(w, random);
        copyPoint(space.x, first, centres, 0, d);
        space.prepare(centres, 0);
        for (int i = 0; i < n; i++) {
            minDistance[i] = space.squaredDistance(i, centres, 0) * w[i];
        }
        for (int j = 1; j < space.k; j++) {
            checkInterrupted();
            int pick = sample(minDistance, random);
            copyPoint(space.x, pick, centres, j, d);
            space.prepare(centres, j);
            for (int i = 0; i < n; i++) {
                minDistance[i] = Math.min(minDistance[i], space.squaredDistance(i, centres, j) * w[i]);
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

    private static Fit lloyd(Space space, double[] w, double[] centres, Buffers buffers, int maxIterations) {
        int n = space.n;
        space.prepareAll(centres);
        var labels = new int[n];
        var distance = buffers.distance;
        var bounds = buffers.bounds;
        var previous = buffers.previous;
        int iterations = 0;
        for (; iterations < maxIterations; iterations++) {
            checkInterrupted();
            boolean changed = iterations == 0
                    ? assignFirst(space, centres, labels, bounds)
                    : assignBounded(space, centres, labels, bounds);
            if (!changed && iterations > 0) {
                break;
            }
            System.arraycopy(centres, 0, previous, 0, centres.length);
            update(space, w, labels, centres, buffers);
            space.prepareAll(centres);
            bounds.moved(previous, centres, labels, space.d);
        }
        if (iterations == maxIterations) {
            // Stopped by the cap after an update: as scikit-learn, label and score against the centres returned.
            assign(space, centres, labels);
        }
        double inertia = 0;
        for (int i = 0; i < n; i++) {
            distance[i] = space.squaredDistance(i, centres, labels[i]);
            inertia += w[i] * distance[i];
        }
        return new Fit(labels, centres, inertia, iterations);
    }

    /** Every point to its nearest centre, the lowest index on a tie. */
    private static void assign(Space space, double[] centres, int[] labels) {
        for (int i = 0; i < space.n; i++) {
            int bestJ = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int j = 0; j < space.k; j++) {
                double s = space.squaredDistance(i, centres, j);
                if (s < bestDistance) {
                    bestDistance = s;
                    bestJ = j;
                }
            }
            labels[i] = bestJ;
        }
    }

    /** {@link #assign} that also sets every bound exactly. True when a label changed. */
    private static boolean assignFirst(Space space, double[] centres, int[] labels, Bounds bounds) {
        int k = space.k;
        boolean changed = false;
        for (int i = 0; i < space.n; i++) {
            int bestJ = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int j = 0; j < k; j++) {
                double s = space.squaredDistance(i, centres, j);
                bounds.lower[i * k + j] = Math.sqrt(s);
                if (s < bestDistance) {
                    bestDistance = s;
                    bestJ = j;
                }
            }
            if (labels[i] != bestJ) {
                labels[i] = bestJ;
                changed = true;
            }
            bounds.upper[i] = Math.sqrt(bestDistance);
        }
        return changed;
    }

    /**
     * Elkan's assignment: a point whose upper bound is within half the distance from its centre to the nearest other
     * centre keeps its label unmeasured; otherwise it is measured only against the centres its bounds do not rule out,
     * its own centre first. True when a label changed.
     */
    private static boolean assignBounded(Space space, double[] centres, int[] labels, Bounds bounds) {
        int k = space.k;
        bounds.centreGaps(centres, k, space.d);
        var upper = bounds.upper;
        var lower = bounds.lower;
        var half = bounds.half;
        boolean changed = false;
        for (int i = 0; i < space.n; i++) {
            int label = labels[i];
            double upperBound = upper[i];
            if (upperBound <= bounds.nearestHalf[label]) {
                continue;
            }
            boolean tight = false;
            int row = i * k;
            for (int j = 0; j < k; j++) {
                if (j == label) {
                    continue;
                }
                // Centre j can't be nearer than this: its lower bound, or half its distance to the current centre.
                double bound = Math.max(lower[row + j], half[label * k + j]);
                if (upperBound <= bound) {
                    continue;
                }
                if (!tight) {
                    upperBound = Math.sqrt(space.squaredDistance(i, centres, label));
                    lower[row + label] = upperBound;
                    tight = true;
                    if (upperBound <= bound) {
                        continue;
                    }
                }
                double distanceToJ = Math.sqrt(space.squaredDistance(i, centres, j));
                lower[row + j] = distanceToJ;
                if (distanceToJ < upperBound) {
                    label = j;
                    upperBound = distanceToJ;
                }
            }
            upper[i] = upperBound;
            if (label != labels[i]) {
                labels[i] = label;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Weighted means of the clusters. An empty cluster gets a point from {@link #reseedEmpty} first, which reads the
     * centres before the update.
     */
    private static void update(Space space, double[] w, int[] labels, double[] centres, Buffers buffers) {
        int n = space.n;
        int d = space.d;
        int k = space.k;
        var x = space.x;
        var sum = buffers.sum;
        var weightSum = buffers.weightSum;
        Arrays.fill(sum, 0);
        Arrays.fill(weightSum, 0);
        for (int i = 0; i < n; i++) {
            int j = labels[i];
            weightSum[j] += w[i];
            for (int t = 0; t < d; t++) {
                sum[j * d + t] += w[i] * x[i * d + t];
            }
        }
        boolean anyEmpty = false;
        for (int j = 0; j < k; j++) {
            anyEmpty |= weightSum[j] <= 0;
        }
        if (anyEmpty) {
            reseedEmpty(space, w, labels, centres, buffers);
        }
        for (int j = 0; j < k; j++) {
            if (weightSum[j] > 0) {
                for (int t = 0; t < d; t++) {
                    centres[j * d + t] = sum[j * d + t] / weightSum[j];
                }
            }
        }
    }

    /**
     * Moves each empty cluster to the point with the largest weighted distance to its own centre that no other empty
     * cluster took this round; the next assignment then gives it that point. The bounds keep no exact distance, so each
     * point's is measured first, to its centre before the update, in the direct form: a point on its centre is then
     * exactly 0, as in plain Lloyd, not the dot form's rounding noise, which would pick another point among duplicate
     * ones. It reads only the centres of clusters with points and writes only the empty ones.
     */
    private static void reseedEmpty(Space space, double[] w, int[] labels, double[] centres, Buffers buffers) {
        int n = space.n;
        int d = space.d;
        int k = space.k;
        var x = space.x;
        var weightSum = buffers.weightSum;
        var distance = buffers.distance;
        for (int i = 0; i < n; i++) {
            distance[i] = space.directSquaredDistance(i, centres, labels[i]);
        }
        // Points already copied into an empty cluster this round; cleared again below, so the buffer stays all false.
        var taken = buffers.taken;
        var picked = buffers.picked;
        int pickedCount = 0;
        for (int j = 0; j < k; j++) {
            if (weightSum[j] > 0) {
                continue;
            }
            int far = -1;
            for (int i = 0; i < n; i++) {
                if (!taken[i] && (far < 0 || w[i] * distance[i] > w[far] * distance[far])) {
                    far = i;
                }
            }
            taken[far] = true;
            picked[pickedCount++] = far;
            copyPoint(x, far, centres, j, d);
        }
        for (int p = 0; p < pickedCount; p++) {
            taken[picked[p]] = false;
        }
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

    /** The points with their squared norms, and the squared norms of the current centres. */
    private static final class Space {

        private final float[] x;
        private final int n;
        private final int d;
        private final int k;
        private final double[] pointNorms;
        private final double[] centreNorms;

        Space(float[] x, int n, int d, int k) {
            this.x = x;
            this.n = n;
            this.d = d;
            this.k = k;
            this.pointNorms = new double[n];
            this.centreNorms = new double[k];
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int t = 0; t < d; t++) {
                    s += (double) x[i * d + t] * x[i * d + t];
                }
                pointNorms[i] = s;
            }
        }

        void prepareAll(double[] centres) {
            for (int j = 0; j < k; j++) {
                prepare(centres, j);
            }
        }

        /** Keeps centre {@code j}'s squared norm: called whenever the centre changes. */
        void prepare(double[] centres, int j) {
            double s = 0;
            for (int t = 0; t < d; t++) {
                s += centres[j * d + t] * centres[j * d + t];
            }
            centreNorms[j] = s;
        }

        /** Never negative: rounding can take |x|² + |c|² - 2 x·c below 0 for a point on its centre. */
        double squaredDistance(int i, double[] centres, int j) {
            return Math.max(0, pointNorms[i] + centreNorms[j] - 2 * VectorMath.dot(x, i * d, centres, j * d, d));
        }

        /** The sum of {@code (x - c)²} over the dimensions, in scalar double: exactly 0 for a point on its centre. */
        double directSquaredDistance(int i, double[] centres, int j) {
            double s = 0;
            int xi = i * d;
            int cj = j * d;
            for (int t = 0; t < d; t++) {
                double v = x[xi + t] - centres[cj + t];
                s += v * v;
            }
            return s;
        }
    }

    /**
     * Elkan's bounds, as distances (not squared): {@code upper} per point to its centre, {@code lower} per point and
     * centre, and per centre pair half the distance between them, with each centre's nearest such half.
     */
    private static final class Bounds {

        private final double[] upper;
        private final double[] lower;
        private final double[] half;
        private final double[] nearestHalf;
        private final double[] shift;

        Bounds(int n, int k) {
            this.upper = new double[n];
            this.lower = new double[Math.multiplyExact(n, k)];
            this.half = new double[k * k];
            this.nearestHalf = new double[k];
            this.shift = new double[k];
        }

        void centreGaps(double[] c, int k, int d) {
            Arrays.fill(nearestHalf, Double.MAX_VALUE);
            for (int j = 0; j < k; j++) {
                for (int j2 = j + 1; j2 < k; j2++) {
                    double s = 0;
                    for (int t = 0; t < d; t++) {
                        double v = c[j * d + t] - c[j2 * d + t];
                        s += v * v;
                    }
                    double h = 0.5 * Math.sqrt(s);
                    half[j * k + j2] = h;
                    half[j2 * k + j] = h;
                    nearestHalf[j] = Math.min(nearestHalf[j], h);
                    nearestHalf[j2] = Math.min(nearestHalf[j2], h);
                }
            }
        }

        /** Loosens the bounds by how far each centre moved in the update. */
        void moved(double[] previous, double[] centres, int[] labels, int d) {
            int k = shift.length;
            for (int j = 0; j < k; j++) {
                double s = 0;
                for (int t = 0; t < d; t++) {
                    double v = centres[j * d + t] - previous[j * d + t];
                    s += v * v;
                }
                shift[j] = Math.sqrt(s);
            }
            for (int i = 0; i < upper.length; i++) {
                upper[i] += shift[labels[i]];
                int row = i * k;
                for (int j = 0; j < k; j++) {
                    lower[row + j] = Math.max(0, lower[row + j] - shift[j]);
                }
            }
        }
    }

    /**
     * The large per-fit buffers, shared by the restarts: each restart overwrites them before it reads them (the first
     * assignment sets every bound, an update clears the sums).
     */
    private static final class Buffers {

        private final Bounds bounds;
        private final double[] distance;
        private final double[] previous;
        private final double[] sum;
        private final double[] weightSum;
        private final boolean[] taken;
        private final int[] picked;

        Buffers(int n, int k, int d) {
            this.bounds = new Bounds(n, k);
            this.distance = new double[n];
            this.previous = new double[k * d];
            this.sum = new double[k * d];
            this.weightSum = new double[k];
            this.taken = new boolean[n];
            this.picked = new int[k];
        }
    }

    private record Fit(int[] labels, double[] centres, double inertia, int iterations) {
    }
}
