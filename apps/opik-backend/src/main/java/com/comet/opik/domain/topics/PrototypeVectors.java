package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A non-empty list of prototype vectors, each {@link TopicEmbedding#DIMENSIONS} long, in the order the run gave them.
 * Stored as one BLOB: a topic's vectors in {@code topics.vectors}, the known-Other ones in
 * {@code topic_projects.other_vectors}. The order is part of the value: assignment breaks a tie between two
 * prototypes by it.
 */
public record PrototypeVectors(@NonNull List<float @NonNull []> vectors) {

    public PrototypeVectors {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("prototype vectors must not be empty");
        }
        var copies = new ArrayList<float[]>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != TopicEmbedding.DIMENSIONS) {
                throw new IllegalArgumentException("prototype vector %d has %d values, expected %d"
                        .formatted(i, vector == null ? 0 : vector.length, TopicEmbedding.DIMENSIONS));
            }
            copies.add(vector.clone());
        }
        vectors = List.copyOf(copies);
    }

    /** How far from 1 a unit vector's norm may be: float32 rounding of a normalised vector. */
    static final double UNIT_TOLERANCE = 1e-3;

    /** {@value TopicEmbedding#DIMENSIONS} values of unit length; a NaN or infinite value fails. */
    static boolean isUnit(@NonNull float[] values) {
        return isUnit(values, TopicEmbedding.DIMENSIONS);
    }

    /** As {@link #isUnit(float[])}, for {@code dimensions} values: the night's check of its prototypes. */
    static boolean isUnit(@NonNull float[] values, int dimensions) {
        return values.length == dimensions && Math.abs(norm(values) - 1) <= UNIT_TOLERANCE;
    }

    /** Fails unless prototype {@code index} is a unit vector of {@value TopicEmbedding#DIMENSIONS} values. */
    static void checkUnit(int index, float[] values) {
        if (values == null || !isUnit(values)) {
            throw new IllegalArgumentException("prototype vector '%s' has '%s' values and norm '%s', expected '%s' "
                    .formatted(index, values == null ? 0 : values.length, values == null ? 0 : norm(values),
                            TopicEmbedding.DIMENSIONS)
                    + "values of unit length");
        }
    }

    static double norm(@NonNull float[] values) {
        double sum = 0;
        for (float value : values) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    public int size() {
        return vectors.size();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PrototypeVectors that) || vectors.size() != that.vectors.size()) {
            return false;
        }
        for (int i = 0; i < vectors.size(); i++) {
            if (!Arrays.equals(vectors.get(i), that.vectors.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (float[] vector : vectors) {
            hash = 31 * hash + Arrays.hashCode(vector);
        }
        return hash;
    }

    @Override
    public String toString() {
        return "PrototypeVectors[size=%d]".formatted(vectors.size());
    }
}
