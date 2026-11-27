package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.hash.Hashing;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The k-means algorithm through its entry points: a discovery run's sizing and author call, and its labeller. */
@DisplayName("k-means topic algorithm")
class KMeansTopicAlgorithmTest {

    private static final int D = TopicEmbedding.DIMENSIONS;
    /** The digest of {@link #labellerDigest}'s labels by each {@link KMeansTopicAlgorithm#LABELLER_VERSION}. */
    private static final Map<Integer, String> LABELLER_DIGESTS = Map.of(
            1, "f71048c4e7f458c774092a384c0ff3880def6880b6ff3ef1ec0159ad20eca9e1");
    private final KMeansTopicAlgorithm algorithm = TopicsTestAlgorithm.algorithm();
    private final String workspaceId = RandomStringUtils.secure().nextAlphanumeric(10);
    private final UUID projectId = TopicsTestAlgorithm.ID_GENERATOR.generateId();

    @ParameterizedTest(name = "{0} unique texts make {1} groups with {2} examples each")
    @CsvSource({"1068, 35, 7", "481, 30, 8", "4622, 50, 5", "20, 20, 10"})
    @DisplayName("a discovery run makes one group per 30 unique texts, at least 30, at most 50 or the unique texts, and "
            + "shows round(250 / k) texts per group, from 5 to 10")
    void sizing(int uniqueTexts, int expectedGroups, int expectedExamples) throws IOException {
        var stats = algorithm.cluster(randomWindow(uniqueTexts), 10).stats();

        assertThat(stats.get("unique_texts").asInt()).isEqualTo(uniqueTexts);
        assertThat(stats.get("k").asInt()).isEqualTo(expectedGroups);
        assertThat(stats.get("author_examples").asInt()).isEqualTo(expectedExamples);
    }

    @Test
    @DisplayName("a discovery run keeps each trace's cosine to the group centres while the group sums stay under half "
            + "as many as the traces, and keeps the vectors when one tight theme needs a sum per trace")
    void cosineTableOrVectors() {
        assertThat(algorithm.cluster(randomWindow(1068), 10).stats().get("assign_input").asText())
                .isEqualTo("cosines");
        assertThat(algorithm.cluster(tightWindow(3_000), 10).stats().get("assign_input").asText())
                .isEqualTo("vectors");
    }

    @Test
    @DisplayName("a merged topic's rename examples take one from each former group in turn, up to the cap")
    void mergeExamples() {
        var byGroup = List.of(List.of("a1", "a2", "a3"), List.of("b1"), List.<String>of());

        assertThat(KMeansTopicAlgorithm.mergeExamples(byGroup, 4)).containsExactly("a1", "b1", "a2", "a3");
        assertThat(KMeansTopicAlgorithm.mergeExamples(byGroup, 2)).containsExactly("a1", "b1");
        assertThat(KMeansTopicAlgorithm.mergeExamples(byGroup, 10)).containsExactly("a1", "b1", "a2", "a3");
        assertThat(KMeansTopicAlgorithm.mergeExamples(List.of(List.of(), List.of()), 4)).isEmpty();
    }

    @Test
    @DisplayName("a verify budget above the inference service's max_tokens limit stops the start")
    void verifyBudgetAboveTheLimit() {
        // The verify task's 60 + 25 per pair x 200 groups is 5060, above the 4096 the service takes.
        var config = TopicsTestAlgorithm.config();
        config.setNightlyMaxGroups(200);

        assertThatThrownBy(() -> TopicsTestAlgorithm.algorithm(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5060")
                .hasMessageContaining("4096");
    }

    @Test
    @DisplayName("the author call is the author task: its prompt with the groups filled in, its schema, max tokens and "
            + "version")
    void authorCall() throws IOException {
        var task = new TopicTasks().author();
        var clustering = algorithm.cluster(randomWindow(40), 3);

        var discovered = TopicsTestAlgorithm.finished(clustering.finish(AuthorAnswer.parse(emptyAnswer())));

        assertThat(clustering.author()).satisfies(call -> {
            assertThat(call.call()).isEqualTo(LlmCall.AUTHOR);
            assertThat(call.schema()).isEqualTo(task.schema());
            assertThat(call.maxTokens()).isEqualTo(task.effectiveMaxTokens());
            assertThat(call.prompt()).contains("at most 3 topics", "  cluster 0: ")
                    .doesNotContain(TopicTasks.MAX_TOPICS_PLACEHOLDER, TopicTasks.CLUSTERS_PLACEHOLDER);
        });
        assertThat(clustering.stats().get("author_task_version").asInt()).isEqualTo(task.version());
        assertThat(discovered.topics()).isEmpty();
        assertThat(discovered.labels()).containsOnly(TopicAlgorithm.OTHER);
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
        var labeller = algorithm.labeller(List.of(topicB, topicA), vectors(axis(3)));

        var labels = labeller.label(List.of(axis(0), axis(1), new float[3], axis(2), axis(3), axis(4)));

        assertThat(labels).containsExactly(topicB.id(), topicA.id(), null, topicB.id(), TraceTopic.OTHER,
                TraceTopic.OTHER);
    }

    @Test
    @DisplayName("a saved list with no topic labels every usable vector Other, as its run did")
    void noTopic() {
        assertThat(algorithm.labeller(List.of(), vectors(axis(0))).label(List.of(axis(1), new float[3])))
                .containsExactly(TraceTopic.OTHER, null);
        assertThat(algorithm.labeller(List.of(), null).label(List.of(axis(2)))).containsExactly(TraceTopic.OTHER);
    }

    @Test
    @DisplayName("the labeller's output on fixed vectors is the one its LABELLER_VERSION recorded: a change of the "
            + "output needs a new version, so saved lists relabel their windows")
    void labellerVersionHoldsItsOutput() {
        assertThat(labellerDigest())
                .as("the labeller's output changed: raise LABELLER_VERSION and add its digest to LABELLER_DIGESTS")
                .isEqualTo(LABELLER_DIGESTS.get(KMeansTopicAlgorithm.LABELLER_VERSION));
    }

    /**
     * Labels 2,000 seeded vectors, each a prototype or a random direction with noise of a random size, so the
     * cosines span both thresholds, with a list of four topics of two prototypes each and two known-Other vectors.
     * The digest is over each label's topic index, -1 for Other, so it does not depend on the ids.
     */
    private String labellerDigest() {
        var random = new SplittableRandom(9410);
        var topics = IntStream.range(0, 4).mapToObj(i -> topic(randomUnit(random), randomUnit(random))).toList();
        var other = vectors(randomUnit(random), randomUnit(random));
        var bases = new ArrayList<float[]>();
        topics.forEach(topic -> bases.addAll(topic.vectors().vectors()));
        bases.addAll(other.vectors());
        var fixture = IntStream.range(0, 2_000).mapToObj(i -> {
            var base = random.nextInt(bases.size() + 1) == bases.size()
                    ? randomUnit(random)
                    : bases.get(random.nextInt(bases.size()));
            double noise = random.nextDouble() * 0.12;
            var vector = new float[D];
            for (int t = 0; t < D; t++) {
                vector[t] = (float) (base[t] + noise * random.nextGaussian());
            }
            return TopicEmbedding.toUnit(vector);
        }).toList();
        var ids = topics.stream().map(Topic::id).toList();
        var hasher = Hashing.sha256().newHasher();
        for (var label : algorithm.labeller(topics, other).label(fixture)) {
            hasher.putInt(ids.indexOf(label));
        }
        return hasher.hash().toString();
    }

    private static float[] randomUnit(SplittableRandom random) {
        var vector = new float[D];
        for (int t = 0; t < D; t++) {
            vector[t] = (float) random.nextGaussian();
        }
        return TopicEmbedding.toUnit(vector);
    }

    private static JsonNode emptyAnswer() throws IOException {
        return InferenceJson.MAPPER.readTree("{\"topics\": [], \"assignment\": []}");
    }

    /** {@code unique} distinct texts, each with a random unit vector. */
    /** Unique texts around one point: every group centre is within otherGate of the others, so no sum is shared. */
    private static TopicWindow tightWindow(int unique) {
        var random = new SplittableRandom(unique);
        var base = new float[D];
        for (int t = 0; t < D; t++) {
            base[t] = (float) random.nextGaussian();
        }
        var vectors = new float[unique * D];
        for (int i = 0; i < unique; i++) {
            double norm = 0;
            for (int t = 0; t < D; t++) {
                vectors[i * D + t] = base[t] + (float) (random.nextGaussian() * 0.3);
                norm += (double) vectors[i * D + t] * vectors[i * D + t];
            }
            for (int t = 0; t < D; t++) {
                vectors[i * D + t] /= (float) Math.sqrt(norm);
            }
        }
        var texts = IntStream.range(0, unique).mapToObj(i -> "text " + i).toArray(String[]::new);
        return TopicWindow.builder().texts(texts).vectors(vectors).build();
    }

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
        return TopicsTestAlgorithm.topic(workspaceId, projectId).vectors(vectors(vectors)).build();
    }

    private static PrototypeVectors vectors(float[]... vectors) {
        return new PrototypeVectors(List.of(vectors));
    }

    private static float[] axis(int index) {
        var vector = new float[D];
        vector[index] = 1;
        return vector;
    }

}
