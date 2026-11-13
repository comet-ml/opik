package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The k-means algorithm's sizing rules and its labeller of a saved list. */
@DisplayName("k-means topic algorithm")
class KMeansTopicAlgorithmTest {

    private static final int D = TopicsRebuildService.DIMENSIONS;
    private static final String WORKSPACE_ID = "ws-1";

    private final KMeansTopicAlgorithm algorithm = new KMeansTopicAlgorithm();
    private final UUID projectId = UUID.randomUUID();

    @ParameterizedTest(name = "{0} unique texts make {1} groups")
    @CsvSource({"1068, 35", "481, 30", "4622, 50", "20, 20"})
    @DisplayName("fine groups: one per 30 unique texts, at least 30, at most 50 or the unique texts")
    void groups(int uniqueTexts, int expectedGroups) {
        assertThat(KMeansTopicAlgorithm.groups(uniqueTexts)).isEqualTo(expectedGroups);
    }

    @Test
    @DisplayName("texts per group: round(250 / k), from 5 to 10")
    void budget() {
        assertThat(KMeansTopicAlgorithm.authorExampleCount(50)).isEqualTo(5);
        assertThat(KMeansTopicAlgorithm.authorExampleCount(35)).isEqualTo(7);
        assertThat(KMeansTopicAlgorithm.authorExampleCount(30)).isEqualTo(8);
        assertThat(KMeansTopicAlgorithm.authorExampleCount(15)).isEqualTo(10);
    }

    /**
     * Rows in position order: topic B, topic A, topic B again, then a known-Other one. Each vector near a row takes that
     * row's topic; one near the known-Other row, or far from all, is Other.
     */
    @Test
    @DisplayName("the labeller follows the saved order: a topic per row with a topic id, Other for the known-Other "
            + "rows and for vectors far from every row")
    void labellerFromSavedOrder() {
        var topicA = UUID.randomUUID();
        var topicB = UUID.randomUUID();
        var labeller = algorithm.labeller(List.of(
                prototype(0, topicB, axis(0)),
                prototype(1, topicA, axis(1)),
                prototype(2, topicB, axis(2)),
                prototype(3, null, axis(3)))).orElseThrow();

        var labels = labeller.label(concat(axis(0), axis(1), axis(2), axis(3), axis(4)));

        assertThat(labels).containsExactly(topicB, topicA, topicB, TraceTopic.OTHER, TraceTopic.OTHER);
    }

    @Test
    @DisplayName("a saved list with no topic prototype has no labeller")
    void noTopicPrototype() {
        assertThat(algorithm.labeller(List.of(prototype(0, null, axis(0))))).isEmpty();
        assertThat(algorithm.labeller(List.of())).isEmpty();
    }

    private TopicPrototype prototype(int position, UUID topicId, float[] vector) {
        return TopicPrototype.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .position(position)
                .topicId(topicId)
                .vector(TopicPrototype.encode(vector))
                .build();
    }

    private static float[] axis(int index) {
        var vector = new float[D];
        vector[index] = 1;
        return vector;
    }

    private static float[] concat(float[]... vectors) {
        var out = new float[vectors.length * D];
        for (int i = 0; i < vectors.length; i++) {
            System.arraycopy(vectors[i], 0, out, i * D, D);
        }
        return out;
    }
}
