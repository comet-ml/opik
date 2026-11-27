package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.BitSet;
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
     * As {@link #merge}, with no copy of the vectors: permutes {@code vectors}' rows in place so that row {@code u}
     * holds the vector of the {@code u}-th unique text (the same values {@link #merge} copies, in the same order), and
     * the other rows follow. The merged vectors are then {@code vectors} itself, {@code size()} rows read from its
     * start. {@link InPlace#restore} puts every row back; until then no other code may read {@code vectors}.
     *
     * @param texts   per trace, in time order (earliest first), stripped and non-empty
     * @param vectors per trace, {@code texts.length * dimensions} values
     */
    static InPlace mergeInPlace(@NonNull String[] texts, @NonNull float[] vectors, int dimensions) {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions,
                "vectors must hold one row per text, texts '%s', values '%s', dimensions '%s'", texts.length,
                vectors.length, dimensions);
        var groups = group(texts);
        // order[p]: the row that moves to row p. The unique texts' first traces first, then the other rows in order.
        var order = new int[texts.length];
        var first = new BitSet(texts.length);
        int p = 0;
        for (int row : groups.firstIndex()) {
            order[p++] = row;
            first.set(row);
        }
        for (int row = 0; row < texts.length; row++) {
            if (!first.get(row)) {
                order[p++] = row;
            }
        }
        permute(vectors, order, dimensions);
        var merged = MergedIntents.builder()
                .texts(groups.texts())
                .weights(groups.weights())
                .firstIndex(groups.firstIndex())
                .textOfTrace(groups.textOfTrace())
                .vectors(vectors)
                .dimensions(dimensions)
                .build();
        return new InPlace(merged, order);
    }

    /** {@link #mergeInPlace}'s output; {@code merged.vectors()} holds more rows than {@code merged.size()}. */
    record InPlace(MergedIntents merged, int[] order) {

        /** Puts every row of the vectors back where {@link #mergeInPlace} found it. */
        void restore() {
            var inverse = new int[order.length];
            for (int p = 0; p < order.length; p++) {
                inverse[order[p]] = p;
            }
            permute(merged.vectors(), inverse, merged.dimensions());
        }
    }

    /** Row {@code p} of {@code x} becomes the old row {@code order[p]}, in place, following the permutation's cycles. */
    private static void permute(float[] x, int[] order, int dimensions) {
        var done = new BitSet(order.length);
        var held = new float[dimensions];
        for (int start = 0; start < order.length; start++) {
            if (done.get(start) || order[start] == start) {
                continue;
            }
            System.arraycopy(x, start * dimensions, held, 0, dimensions);
            int to = start;
            done.set(to);
            // Each step marks a row not marked before, so the cycle ends within order.length steps.
            for (int from = order[to]; from != start; from = order[to]) {
                Preconditions.checkState(!done.get(from), "order must be a permutation, repeated row '%s'", from);
                System.arraycopy(x, from * dimensions, x, to * dimensions, dimensions);
                to = from;
                done.set(to);
            }
            System.arraycopy(held, 0, x, to * dimensions, dimensions);
        }
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
