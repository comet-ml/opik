package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Assigns traces to the nearest prototype. The prototypes are the topics' (each owned by a topic index) followed by
 * the known-Other ones; a known-Other prototype within cosine {@value #OTHER_GATE} of any topic prototype is left out,
 * so it cannot pull a topic's traces into Other. A trace takes its nearest prototype's owner when the cosine is at least
 * {@value #ASSIGN_MIN_COSINE}, else Other ({@link #OTHER}). Ties go to the lower prototype index. All vectors are unit
 * length, so the cosine is the dot product.
 */
public class PrototypeAssigner {

    public static final int OTHER = -1;
    static final double ASSIGN_MIN_COSINE = 0.75;
    static final double OTHER_GATE = 0.95;

    private final float[] prototypes;
    private final int[] owners;
    private final int dimensions;
    private final int gatedOther;

    /**
     * @param topicPrototypes per prototype, {@code dimensions} values, in prototype order
     * @param topicOwners     the topic index of each topic prototype
     * @param otherPrototypes the known-Other prototypes, in prototype order
     */
    public PrototypeAssigner(@NonNull List<float[]> topicPrototypes, @NonNull List<Integer> topicOwners,
            @NonNull List<float[]> otherPrototypes, int dimensions) {
        Preconditions.checkArgument(topicPrototypes.size() == topicOwners.size(),
                "every topic prototype needs an owner");
        var kept = new ArrayList<float[]>(topicPrototypes);
        var keptOwners = new ArrayList<>(topicOwners);
        int gated = 0;
        for (var other : otherPrototypes) {
            boolean nearTopic = false;
            for (var topic : topicPrototypes) {
                if (dot(other, 0, topic, 0, dimensions) >= OTHER_GATE) {
                    nearTopic = true;
                    break;
                }
            }
            if (nearTopic) {
                gated++;
            } else {
                kept.add(other);
                keptOwners.add(OTHER);
            }
        }
        this.dimensions = dimensions;
        this.gatedOther = gated;
        this.prototypes = new float[kept.size() * dimensions];
        this.owners = new int[kept.size()];
        for (int p = 0; p < kept.size(); p++) {
            Preconditions.checkArgument(kept.get(p).length == dimensions,
                    "a prototype must have '%s' values, got '%s'", dimensions, kept.get(p).length);
            System.arraycopy(kept.get(p), 0, prototypes, p * dimensions, dimensions);
            owners[p] = keptOwners.get(p);
        }
    }

    /** The owner per trace: a topic index or {@link #OTHER}. */
    public int[] assign(@NonNull float[] vectors) {
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        int n = vectors.length / dimensions;
        var out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = nearest(vectors, i).owner();
        }
        return out;
    }

    Match nearest(float[] vectors, int i) {
        int best = -1;
        double bestCosine = Double.NEGATIVE_INFINITY;
        for (int p = 0; p < owners.length; p++) {
            double cosine = dot(vectors, i * dimensions, prototypes, p * dimensions, dimensions);
            if (cosine > bestCosine) {
                bestCosine = cosine;
                best = p;
            }
        }
        if (best < 0 || bestCosine < ASSIGN_MIN_COSINE) {
            return new Match(OTHER, best, bestCosine);
        }
        return new Match(owners[best], best, bestCosine);
    }

    public int prototypeCount() {
        return owners.length;
    }

    public int gatedOther() {
        return gatedOther;
    }

    /** The prototype at {@code position}, in the order a tie is broken by: the topic ones, then the kept Other ones. */
    public float[] prototype(int position) {
        return Arrays.copyOfRange(prototypes, position * dimensions, (position + 1) * dimensions);
    }

    /** The owner of the prototype at {@code position}: a topic index or {@link #OTHER}. */
    public int owner(int position) {
        return owners[position];
    }

    private static double dot(float[] a, int aOffset, float[] b, int bOffset, int dimensions) {
        double s = 0;
        for (int t = 0; t < dimensions; t++) {
            s += (double) a[aOffset + t] * b[bOffset + t];
        }
        return s;
    }

    @Builder
    record Match(int owner, int prototype, double cosine) {
    }
}
