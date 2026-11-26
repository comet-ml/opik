package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topics: merge of topics that cover the same traces")
class TopicMergerTest {

    @Test
    @DisplayName("folds the smaller of two topics with near centres into the larger, leaves a distinct one alone")
    void foldsNearPairOnly() {
        float c = (float) Math.cos(0.1);
        float s = (float) Math.sin(0.1);
        // topic 0: three traces at angle 0.1 and -0.1 around east; topic 1: two traces at east; topic 2: north
        float[] vectors = {c, s, c, -s, c, s, 1, 0, 1, 0, 0, 1};
        int[] labels = {0, 0, 0, 1, 1, 2};

        var merges = TopicMerger.merges(vectors, labels, 3, new int[]{0, 1, 2}, 2, 0.96);

        assertThat(merges).extracting(TopicMerger.Merge::kept, TopicMerger.Merge::absorbed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 1));
        assertThat(TopicMerger.merges(vectors, labels, 3, new int[]{0, 1, 2}, 2, 2.0)).isEmpty();
    }
}
