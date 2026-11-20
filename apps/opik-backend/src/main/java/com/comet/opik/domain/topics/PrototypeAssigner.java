package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Assigns traces to the nearest prototype. The prototypes are the topics' (each owned by a topic index) followed by
 * the known-Other ones; a known-Other prototype within cosine {@code otherGate} of any topic prototype is left out,
 * so it cannot pull a topic's traces into Other. A trace takes its nearest prototype's owner when the cosine is at least
 * {@code minCosine}, else Other ({@link TopicAlgorithm#OTHER}). Ties go to the lower prototype index. All vectors are
 * unit length, so the cosine is the dot product.
 */
public class PrototypeAssigner {

    /** A trace with no prototype at {@code minCosine} or more has no winner. */
    static final int NO_WINNER = -1;

    private final float[] prototypes;
    private final int[] owners;
    private final int dimensions;
    private final double minCosine;
    private final int gatedOther;
    private final List<float[]> keptOther;

    /**
     * @param topicPrototypes per prototype, {@code dimensions} values, in prototype order
     * @param topicOwners     the topic index of each topic prototype
     * @param otherPrototypes the known-Other prototypes, in prototype order
     * @param minCosine       {@code topics.algorithm.assignMinCosine}
     * @param otherGate       {@code topics.algorithm.otherGate}
     */
    public PrototypeAssigner(@NonNull List<float[]> topicPrototypes, @NonNull List<Integer> topicOwners,
            @NonNull List<float[]> otherPrototypes, int dimensions, double minCosine, double otherGate) {
        Preconditions.checkArgument(topicPrototypes.size() == topicOwners.size(),
                "every topic prototype needs an owner");
        Stream.concat(topicPrototypes.stream(), otherPrototypes.stream())
                .forEach(prototype -> Preconditions.checkArgument(prototype.length == dimensions,
                        "a prototype must have '%s' values, got '%s'", dimensions, prototype.length));
        var kept = otherPrototypes.stream()
                .filter(other -> topicPrototypes.stream()
                        .noneMatch(topic -> dot(other, 0, topic, 0, dimensions) >= otherGate))
                .toList();
        this.dimensions = dimensions;
        this.minCosine = minCosine;
        this.gatedOther = otherPrototypes.size() - kept.size();
        this.keptOther = kept;
        var all = Stream.concat(topicPrototypes.stream(), kept.stream()).toList();
        this.prototypes = new float[all.size() * dimensions];
        IntStream.range(0, all.size())
                .forEach(p -> System.arraycopy(all.get(p), 0, prototypes, p * dimensions, dimensions));
        this.owners = IntStream.concat(topicOwners.stream().mapToInt(Integer::intValue),
                IntStream.generate(() -> TopicAlgorithm.OTHER).limit(kept.size())).toArray();
    }

    /** The owner per trace: a topic index or {@link TopicAlgorithm#OTHER}. */
    public int[] assign(@NonNull float[] vectors) {
        return owners(winners(vectors));
    }

    /**
     * The winning prototype per trace: its index in prototype order (the topic prototypes in the given order, then
     * {@link #keptOther}), or {@link #NO_WINNER} below {@code minCosine}.
     */
    int[] winners(@NonNull float[] vectors) {
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        int n = vectors.length / dimensions;
        var out = new int[n];
        for (int i = 0; i < n; i++) {
            int best = NO_WINNER;
            double bestCosine = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < owners.length; p++) {
                double cosine = dot(vectors, i * dimensions, prototypes, p * dimensions, dimensions);
                if (cosine > bestCosine) {
                    bestCosine = cosine;
                    best = p;
                }
            }
            out[i] = bestCosine < minCosine ? NO_WINNER : best;
        }
        return out;
    }

    /** The owner per winner: a topic index, or {@link TopicAlgorithm#OTHER} for a known-Other prototype or no winner. */
    int[] owners(@NonNull int[] winners) {
        var out = new int[winners.length];
        for (int i = 0; i < winners.length; i++) {
            out[i] = winners[i] == NO_WINNER ? TopicAlgorithm.OTHER : owners[winners[i]];
        }
        return out;
    }

    public int prototypeCount() {
        return owners.length;
    }

    /** How many known-Other prototypes the gate left out. */
    public int gatedOther() {
        return gatedOther;
    }

    /** The known-Other prototypes the gate kept, in prototype order: the ones an assignment can use. */
    public List<float[]> keptOther() {
        return keptOther;
    }

    /**
     * Row {@code a} dotted with row {@code b}, summed in double as eight partial sums over interleaved dimensions,
     * added together in a fixed order, as {@code KMeans} does: it runs once per trace and prototype, and Java never
     * reorders double additions, so the result is the same on every machine.
     */
    private static double dot(float[] a, int aOffset, float[] b, int bOffset, int dimensions) {
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
}
