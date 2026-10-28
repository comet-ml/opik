package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Merges identical intents into weighted points, as the stand's {@code fine_cluster} does: one point per exact text
 * (no further normalization), weighted by its count, represented by the vector of its first trace. The texts are in code
 * point order ({@code np.unique}'s), which fixes the k-means input order.
 */
@UtilityClass
public class IntentMerger {

    /**
     * @param texts   per trace, in time order (earliest first), stripped and non-empty
     * @param vectors per trace, {@code texts.length * dimensions} values
     */
    public static Merged merge(@NonNull String[] texts, @NonNull float[] vectors, int dimensions) {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions,
                "vectors must hold one row per text, texts '%s', values '%s', dimensions '%s'", texts.length,
                vectors.length, dimensions);
        var first = new HashMap<String, Integer>();
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < texts.length; i++) {
            first.putIfAbsent(texts[i], i);
            counts.merge(texts[i], 1, Integer::sum);
        }
        var unique = first.keySet().toArray(String[]::new);
        Arrays.sort(unique, TopicText.CODE_POINT_ORDER);
        var index = new HashMap<String, Integer>(unique.length * 2);
        var weights = new int[unique.length];
        var firstIndex = new int[unique.length];
        var uniqueVectors = new float[unique.length * dimensions];
        for (int u = 0; u < unique.length; u++) {
            index.put(unique[u], u);
            weights[u] = counts.get(unique[u]);
            firstIndex[u] = first.get(unique[u]);
            System.arraycopy(vectors, firstIndex[u] * dimensions, uniqueVectors, u * dimensions, dimensions);
        }
        var inverse = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            inverse[i] = index.get(texts[i]);
        }
        return Merged.builder()
                .texts(unique)
                .weights(weights)
                .firstIndex(firstIndex)
                .inverse(inverse)
                .vectors(uniqueVectors)
                .dimensions(dimensions)
                .build();
    }

    /**
     * The unique texts in code point order, each one's count, first trace and vector, and per trace the index of its
     * text.
     */
    @Builder
    public record Merged(
            @NonNull String[] texts,
            @NonNull int[] weights,
            @NonNull int[] firstIndex,
            @NonNull int[] inverse,
            @NonNull float[] vectors,
            int dimensions) {

        public int size() {
            return texts.length;
        }
    }
}
