package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Merges identical intents into weighted points: one point per exact text (no further normalization), weighted by its
 * count, represented by the vector of its first trace. The texts are in code point order, which fixes the k-means
 * input order.
 */
@UtilityClass
public class IntentMerger {

    /**
     * @param texts   per trace, in time order (earliest first), stripped and non-empty
     * @param vectors per trace, {@code texts.length * dimensions} values
     */
    public static MergedIntents merge(@NonNull String[] texts, @NonNull float[] vectors, int dimensions) {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions,
                "vectors must hold one row per text, texts '%s', values '%s', dimensions '%s'", texts.length,
                vectors.length, dimensions);
        var first = new HashMap<String, Integer>();
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < texts.length; i++) {
            Preconditions.checkArgument(texts[i] != null, "texts must not hold null, index '%s'", i);
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
        var textOfTrace = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            textOfTrace[i] = index.get(texts[i]);
        }
        return MergedIntents.builder()
                .texts(unique)
                .weights(weights)
                .firstIndex(firstIndex)
                .textOfTrace(textOfTrace)
                .vectors(uniqueVectors)
                .dimensions(dimensions)
                .build();
    }
}
