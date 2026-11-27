package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.HashMap;

/**
 * Merges identical intents into weighted points: one point per exact text (no further normalization), weighted by its
 * count, represented by the vector of its first trace. The texts are in code point order, which fixes the k-means
 * input order.
 */
@UtilityClass
public class IntentMerger {

    /** The slots of a text's entry in {@link #group}: its count, its first trace, its index among the sorted texts. */
    private static final int COUNT = 0;
    private static final int FIRST = 1;
    private static final int INDEX = 2;
    private static final int ENTRY_SIZE = 3;

    /**
     * @param texts   per trace, in time order (earliest first), stripped and non-empty
     * @param vectors per trace, {@code texts.length * dimensions} values
     */
    public static MergedIntents merge(@NonNull String[] texts, @NonNull float[] vectors, int dimensions) {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions,
                "vectors must hold one row per text, texts '%s', values '%s', dimensions '%s'", texts.length,
                vectors.length, dimensions);
        var groups = group(texts);
        var firstIndex = groups.firstIndex();
        var uniqueVectors = new float[groups.size() * dimensions];
        for (int u = 0; u < groups.size(); u++) {
            System.arraycopy(vectors, firstIndex[u] * dimensions, uniqueVectors, u * dimensions, dimensions);
        }
        return MergedIntents.builder()
                .texts(groups.texts())
                .weights(groups.weights())
                .firstIndex(firstIndex)
                .textOfTrace(groups.textOfTrace())
                .vectors(uniqueVectors)
                .dimensions(dimensions)
                .build();
    }

    /**
     * The grouping alone, with no vector copied.
     *
     * @param texts per trace, in time order (earliest first), stripped and non-empty
     */
    static IntentGroups group(@NonNull String[] texts) {
        for (int i = 0; i < texts.length; i++) {
            Preconditions.checkArgument(texts[i] != null, "texts must not hold null, index '%s'", i);
        }
        // Hash grouping, then one sort of the unique texts: faster than a sorted map of every trace. Per text, an
        // int array holds its count, its first trace and its index among the sorted texts, so no index is boxed.
        var byText = new HashMap<String, int[]>();
        for (int i = 0; i < texts.length; i++) {
            var entry = byText.get(texts[i]);
            if (entry == null) {
                entry = new int[ENTRY_SIZE];
                entry[FIRST] = i;
                byText.put(texts[i], entry);
            }
            entry[COUNT]++;
        }
        var unique = byText.keySet().stream().sorted(TopicText.CODE_POINT_ORDER).toArray(String[]::new);
        var weights = new int[unique.length];
        var firstIndex = new int[unique.length];
        for (int u = 0; u < unique.length; u++) {
            var entry = byText.get(unique[u]);
            weights[u] = entry[COUNT];
            firstIndex[u] = entry[FIRST];
            entry[INDEX] = u;
        }
        var textOfTrace = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            textOfTrace[i] = byText.get(texts[i])[INDEX];
        }
        return IntentGroups.builder()
                .texts(unique)
                .weights(weights)
                .firstIndex(firstIndex)
                .textOfTrace(textOfTrace)
                .build();
    }
}
