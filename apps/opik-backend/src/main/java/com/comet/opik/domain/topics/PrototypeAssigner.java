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
                        .noneMatch(topic -> VectorMath.dot(other, 0, topic, 0, dimensions) >= otherGate))
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
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        return IntStream.range(0, vectors.length / dimensions).map(i -> nearest(vectors, i)).toArray();
    }

    /** The owner of trace {@code i}'s nearest prototype; {@link TopicAlgorithm#OTHER} below {@code minCosine}. */
    private int nearest(float[] vectors, int i) {
        int best = -1;
        double bestCosine = Double.NEGATIVE_INFINITY;
        for (int p = 0; p < owners.length; p++) {
            double cosine = VectorMath.dot(vectors, i * dimensions, prototypes, p * dimensions, dimensions);
            if (cosine > bestCosine) {
                bestCosine = cosine;
                best = p;
            }
        }
        return best < 0 || bestCosine < minCosine ? TopicAlgorithm.OTHER : owners[best];
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
