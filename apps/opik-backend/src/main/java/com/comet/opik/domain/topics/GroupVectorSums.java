package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * A discovery window's vectors summed before the author call, so that {@link TopicMerger} can check the answer's
 * topics for overlap after the window is gone. Every prototype of a discovery run is a group centre, and a trace takes
 * the owner of its nearest prototype at {@code assignMinCosine} or more ({@link PrototypeAssigner}). Which centres are
 * prototypes depends on the answer only through the Other gate: every group is a topic's or Other, and the gate leaves
 * out a known-Other centre within {@code otherGate} of a topic centre. So a centre with no other centre within
 * {@code otherGate} is a prototype whatever the answer is, and a trace whose nearest centre is such a centre, with no
 * tie, lands in that centre's group: its vector is summed per group. Any other trace at {@code assignMinCosine} or more
 * is summed under its ranking, the centres from the nearest down to the first one that cannot be gated, ties kept
 * together, which the answer resolves as the assigner would. A trace below {@code assignMinCosine} to every centre is
 * Other under any answer and is not summed.
 *
 * <p>A topic's member sum is then the sum of the sums that resolve to it: the same traces the assigner gives it, so the
 * counts are exact and the sums equal up to the order of the additions.
 */
final class GroupVectorSums {

    private final int groups;
    private final int dimensions;
    private final double[][] byGroup;
    private final long[] countByGroup;
    /** Per ranking: the tie sets from the nearest centre, each a sorted array of groups. */
    private final Map<Ranking, Sum> byRanking;

    private GroupVectorSums(int groups, int dimensions) {
        this.groups = groups;
        this.dimensions = dimensions;
        this.byGroup = new double[groups][];
        this.countByGroup = new long[groups];
        this.byRanking = new HashMap<>();
    }

    private record Ranking(@NonNull List<List<Integer>> tieSets) {
    }

    private static final class Sum {
        private final double[] values;
        private long count;

        private Sum(int dimensions) {
            this.values = new double[dimensions];
        }
    }

    /**
     * @param vectors  per trace, {@code dimensions} values
     * @param cosines  per trace, its cosine to each centre ({@link PrototypeAssigner#cosines})
     * @param centres  per group, {@code dimensions} values: the prototypes the answer can make
     * @param groups   the number of groups
     * @param minCosine {@code topics.algorithm.assignMinCosine}
     * @param otherGate {@code topics.algorithm.otherGate}
     */
    static GroupVectorSums of(@NonNull float[] vectors, @NonNull double[] cosines, @NonNull float[] centres,
            int groups, int dimensions, double minCosine, double otherGate) {
        Preconditions.checkArgument(groups > 0 && centres.length == groups * dimensions, "centres must be whole rows");
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        int n = vectors.length / dimensions;
        Preconditions.checkArgument(cosines.length == (long) n * groups, "cosines must hold one row per trace");
        var gateable = gateable(centres, groups, dimensions, otherGate);
        var sums = new GroupVectorSums(groups, dimensions);
        for (int i = 0; i < n; i++) {
            int row = i * groups;
            int best = -1;
            double bestCosine = Double.NEGATIVE_INFINITY;
            boolean tie = false;
            for (int g = 0; g < groups; g++) {
                double cosine = cosines[row + g];
                if (cosine > bestCosine) {
                    bestCosine = cosine;
                    best = g;
                    tie = false;
                } else if (cosine == bestCosine) {
                    tie = true;
                }
            }
            if (!(bestCosine >= minCosine)) {
                continue;
            }
            if (!tie && !gateable[best]) {
                if (sums.byGroup[best] == null) {
                    sums.byGroup[best] = new double[dimensions];
                }
                add(sums.byGroup[best], vectors, i * dimensions, dimensions);
                sums.countByGroup[best]++;
            } else {
                var sum = sums.byRanking.computeIfAbsent(ranking(cosines, row, groups, minCosine, gateable),
                        __ -> new Sum(dimensions));
                add(sum.values, vectors, i * dimensions, dimensions);
                sum.count++;
            }
        }
        return sums;
    }

    /**
     * Per candidate topic, the sum and the count of the members the assigner gives it, for the prototypes of
     * {@code assigner}: prototype {@code p} is centre {@code columnOf[p]} ({@link PrototypeAssigner#columnsOf}).
     */
    TopicMerger.Members members(int topicCount, @NonNull int[] candidates, @NonNull int[] columnOf,
            @NonNull IntUnaryOperator ownerOf) {
        // The assigner breaks a tie by the lower prototype index, so a centre stands for its first prototype.
        var firstPrototype = new int[groups];
        Arrays.fill(firstPrototype, -1);
        for (int p = columnOf.length - 1; p >= 0; p--) {
            firstPrototype[columnOf[p]] = p;
        }
        var members = TopicMerger.Members.of(topicCount, candidates, dimensions);
        for (int g = 0; g < groups; g++) {
            if (byGroup[g] != null) {
                Preconditions.checkState(firstPrototype[g] >= 0, "a centre that cannot be gated has no prototype");
                members.add(ownerOf.applyAsInt(firstPrototype[g]), byGroup[g], countByGroup[g]);
            }
        }
        for (var entry : byRanking.entrySet()) {
            int prototype = winner(entry.getKey(), firstPrototype);
            if (prototype >= 0) {
                members.add(ownerOf.applyAsInt(prototype), entry.getValue().values, entry.getValue().count);
            }
        }
        return members;
    }

    /** How many sums it holds: one per group with a trace summed, and one per ranking. */
    long sums() {
        return Arrays.stream(byGroup).filter(Objects::nonNull).count() + byRanking.size();
    }

    /** How many traces were summed under a ranking rather than per group. */
    long rankedTraces() {
        return byRanking.values().stream().mapToLong(sum -> sum.count).sum();
    }

    /** The winning prototype of a ranking: the first prototype of the first tie set with one, or none. */
    private static int winner(Ranking ranking, int[] firstPrototype) {
        for (var tieSet : ranking.tieSets()) {
            int best = -1;
            for (int g : tieSet) {
                int p = firstPrototype[g];
                if (p >= 0 && (best < 0 || p < best)) {
                    best = p;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    /** Per group, whether another centre is within {@code otherGate} of it, as the assigner's gate compares them. */
    private static boolean[] gateable(float[] centres, int groups, int dimensions, double otherGate) {
        var out = new boolean[groups];
        for (int g = 0; g < groups; g++) {
            var other = Arrays.copyOfRange(centres, g * dimensions, (g + 1) * dimensions);
            for (int h = 0; h < groups && !out[g]; h++) {
                if (h != g) {
                    var topic = Arrays.copyOfRange(centres, h * dimensions, (h + 1) * dimensions);
                    out[g] = VectorMath.dot(other, 0, topic, 0, dimensions) >= otherGate;
                }
            }
        }
        return out;
    }

    /**
     * The centres at {@code minCosine} or more, nearest first, in tie sets of equal cosine, down to the first tie set
     * that holds a centre that cannot be gated.
     */
    private static Ranking ranking(double[] cosines, int row, int groups, double minCosine, boolean[] gateable) {
        var near = IntStream.range(0, groups).filter(g -> cosines[row + g] >= minCosine).boxed()
                .sorted(Comparator.<Integer>comparingDouble(g -> cosines[row + g]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        var tieSets = new ArrayList<List<Integer>>();
        int at = 0;
        while (at < near.size()) {
            double cosine = cosines[row + near.get(at)];
            var tieSet = new ArrayList<Integer>();
            boolean stays = false;
            while (at < near.size() && cosines[row + near.get(at)] == cosine) {
                int g = near.get(at++);
                tieSet.add(g);
                stays |= !gateable[g];
            }
            tieSets.add(List.copyOf(tieSet));
            if (stays) {
                break;
            }
        }
        return new Ranking(List.copyOf(tieSets));
    }

    private static void add(double[] sum, float[] vectors, int offset, int dimensions) {
        for (int j = 0; j < dimensions; j++) {
            sum[j] += vectors[offset + j];
        }
    }
}
