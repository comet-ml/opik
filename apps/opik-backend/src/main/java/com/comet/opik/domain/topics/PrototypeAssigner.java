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
 *
 * <p>The prototypes are kept widened to double, and each trace row is widened once into a buffer before it meets
 * them: the cosines are {@link VectorMath#dot} of the float rows bit for bit, without converting the same trace value
 * again for every prototype.
 */
public class PrototypeAssigner {

    /** A trace with no prototype at {@code minCosine} or more has no winner. */
    static final int NO_WINNER = -1;

    private final double[] prototypes;
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
                        .noneMatch(topic -> VectorMath.dot(other, 0, topic, 0, dimensions) >= otherGate))
                .toList();
        this.dimensions = dimensions;
        this.minCosine = minCosine;
        this.gatedOther = otherPrototypes.size() - kept.size();
        this.keptOther = kept;
        var all = Stream.concat(topicPrototypes.stream(), kept.stream()).toList();
        this.prototypes = new double[all.size() * dimensions];
        for (int p = 0; p < all.size(); p++) {
            var prototype = all.get(p);
            for (int t = 0; t < dimensions; t++) {
                prototypes[p * dimensions + t] = prototype[t];
            }
        }
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
        var cosines = new double[2];
        var row = new double[dimensions];
        int count = owners.length;
        for (int i = 0; i < n; i++) {
            int offset = i * dimensions;
            for (int t = 0; t < dimensions; t++) {
                row[t] = vectors[offset + t];
            }
            int best = NO_WINNER;
            double bestCosine = Double.NEGATIVE_INFINITY;
            int p = 0;
            // Two prototypes per pass read each trace value once; dot2's cosines are dot's bit for bit.
            for (; p + 1 < count; p += 2) {
                VectorMath.dot2(row, 0, prototypes, p * dimensions, (p + 1) * dimensions, dimensions, cosines);
                if (cosines[0] > bestCosine) {
                    bestCosine = cosines[0];
                    best = p;
                }
                if (cosines[1] > bestCosine) {
                    bestCosine = cosines[1];
                    best = p + 1;
                }
            }
            if (p < count) {
                double cosine = VectorMath.dot(row, 0, prototypes, p * dimensions, dimensions);
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

    int prototypeCount() {
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
}
