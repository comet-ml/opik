package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The k-means algorithm through its entry points: a first build's sizing and author call, and its labeller. */
@DisplayName("k-means topic algorithm")
class KMeansTopicAlgorithmTest {

    private static final int D = TopicEmbedding.DIMENSIONS;
    private static final String WORKSPACE_ID = "ws-1";

    private final KMeansTopicAlgorithm algorithm = TopicsTestAlgorithm.algorithm();
    private final UUID projectId = UUID.randomUUID();

    @ParameterizedTest(name = "{0} unique texts make {1} groups with {2} examples each")
    @CsvSource({"1068, 35, 7", "481, 30, 8", "4622, 50, 5", "20, 20, 10"})
    @DisplayName("a first build makes one group per 30 unique texts, at least 30, at most 50 or the unique texts, and "
            + "shows round(250 / k) texts per group, from 5 to 10")
    void sizing(int uniqueTexts, int expectedGroups, int expectedExamples) throws IOException {
        var context = new TopicsTestAlgorithm.StubContext(emptyAnswer());

        algorithm.firstBuild(randomWindow(uniqueTexts), 10, context);

        assertThat(context.stats().get("unique_texts").asInt()).isEqualTo(uniqueTexts);
        assertThat(context.stats().get("k").asInt()).isEqualTo(expectedGroups);
        assertThat(context.stats().get("author_examples").asInt()).isEqualTo(expectedExamples);
    }

    @Test
    @DisplayName("the author call is the author task: its prompt with the groups filled in, its schema, max tokens and "
            + "version")
    void authorCall() throws IOException {
        var task = new TopicTasks().author();
        var context = new TopicsTestAlgorithm.StubContext(emptyAnswer());

        var build = algorithm.firstBuild(randomWindow(40), 3, context);

        assertThat(context.calls).singleElement().satisfies(call -> {
            assertThat(call.call()).isEqualTo(LlmCall.AUTHOR);
            assertThat(call.tag()).isEqualTo(LlmCall.AUTHOR.getValue());
            assertThat(call.schema()).isEqualTo(task.schema());
            assertThat(call.maxTokens()).isEqualTo(task.effectiveMaxTokens());
            assertThat(call.prompt()).contains("at most 3 topics", "  cluster 0: ")
                    .doesNotContain(TopicTasks.MAX_TOPICS_PLACEHOLDER, TopicTasks.CLUSTERS_PLACEHOLDER);
        });
        assertThat(context.stats().get("author_task_version").asInt()).isEqualTo(task.version());
        assertThat(build.topics()).isEmpty();
        assertThat(build.labels()).containsOnly(TopicAlgorithm.OTHER);
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

    private static JsonNode emptyAnswer() throws IOException {
        return InferenceJson.MAPPER.readTree("{\"topics\": [], \"assignment\": []}");
    }

    /** {@code unique} distinct texts, each with a random unit vector. */
    private static TopicWindow randomWindow(int unique) {
        var random = new SplittableRandom(unique);
        var vectors = new float[unique * D];
        for (int i = 0; i < unique; i++) {
            double norm = 0;
            for (int t = 0; t < D; t++) {
                vectors[i * D + t] = (float) random.nextGaussian();
                norm += (double) vectors[i * D + t] * vectors[i * D + t];
            }
            for (int t = 0; t < D; t++) {
                vectors[i * D + t] /= (float) Math.sqrt(norm);
            }
        }
        var texts = IntStream.range(0, unique).mapToObj(i -> "text " + i).toArray(String[]::new);
        return TopicWindow.builder().texts(texts).vectors(vectors).build();
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
