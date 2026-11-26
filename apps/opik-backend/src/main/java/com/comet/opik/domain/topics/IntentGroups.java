package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/**
 * Traces grouped by exact text: the unique texts in code point order, each one's count and first trace, and per trace
 * the index of its text ({@code textOfTrace}). The indices are positions in the texts the grouping was given.
 */
@Builder(toBuilder = true)
record IntentGroups(
        @NonNull String[] texts,
        @NonNull int[] weights,
        @NonNull int[] firstIndex,
        @NonNull int[] textOfTrace) {

    int size() {
        return texts.length;
    }
}
