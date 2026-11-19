package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The k-means algorithm's sizing rules and its labeller of a saved list. */
@DisplayName("k-means topic algorithm")
class KMeansTopicAlgorithmTest {

    private static final int D = TopicEmbedding.DIMENSIONS;
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
     * Topic B with two vectors, then topic A, then a known-Other vector. Each vector near a prototype takes its topic;
     * one near the known-Other prototype, or far from all, is Other.
     */
    @Test
    @DisplayName("the labeller follows the saved list: each topic's vectors, Other for the known-Other vectors and "
            + "for vectors far from every prototype")
    void labellerFromSavedList() {
        var topicA = topic(axis(1));
        var topicB = topic(axis(0), axis(2));
        var labeller = algorithm.labeller(List.of(topicB, topicA), vectors(axis(3))).orElseThrow();

        var labels = labeller.label(concat(axis(0), axis(1), axis(2), axis(3), axis(4)));

        assertThat(labels).containsExactly(topicB.id(), topicA.id(), topicB.id(), TraceTopic.OTHER, TraceTopic.OTHER);
    }

    @Test
    @DisplayName("a saved list with no topic has no labeller")
    void noTopic() {
        assertThat(algorithm.labeller(List.of(), vectors(axis(0)))).isEmpty();
        assertThat(algorithm.labeller(List.of(), null)).isEmpty();
    }

    private Topic topic(float[]... vectors) {
        return Topic.builder()
                .id(UUID.randomUUID())
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .name("t")
                .definition("d")
                .born(LocalDate.of(2026, 11, 19))
                .vectors(vectors(vectors))
                .build();
    }

    private static PrototypeVectors vectors(float[]... vectors) {
        return new PrototypeVectors(List.of(vectors));
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
