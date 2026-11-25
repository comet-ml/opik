package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        // Hash grouping, then one sort of the unique texts: faster than a sorted map of every trace.
        var tracesOfText = IntStream.range(0, texts.length).boxed()
                .collect(Collectors.groupingBy(i -> texts[i]));
        var unique = tracesOfText.keySet().stream().sorted(TopicText.CODE_POINT_ORDER).toArray(String[]::new);
        var traces = Arrays.stream(unique).map(tracesOfText::get).toList();
        var textOfTrace = new int[texts.length];
        for (int u = 0; u < unique.length; u++) {
            for (int i : traces.get(u)) {
                textOfTrace[i] = u;
            }
        }
        return IntentGroups.builder()
                .texts(unique)
                .weights(traces.stream().mapToInt(List::size).toArray())
                .firstIndex(traces.stream().mapToInt(List::getFirst).toArray())
                .textOfTrace(textOfTrace)
                .build();
    }
}
