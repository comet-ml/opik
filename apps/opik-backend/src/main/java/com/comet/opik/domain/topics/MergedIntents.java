package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/**
 * {@link IntentMerger}'s output: the unique texts in code point order, each one's count, first trace and vector, and
 * per trace the index of its text ({@code textOfTrace}).
 */
@Builder(toBuilder = true)
public record MergedIntents(
        @NonNull String[] texts,
        @NonNull int[] weights,
        @NonNull int[] firstIndex,
        @NonNull int[] textOfTrace,
        @NonNull float[] vectors,
        int dimensions) {

    public int size() {
        return texts.length;
    }
}
