package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.inference.InferenceJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Section 6.1 of the stage 2a plan: the Java first build against the stand's outputs on the fixture. The deterministic
 * parts are equal byte for byte given the stand's cluster labels; the k-means passes the quality bar.
 */
@DisplayName("topics first build: the algorithm against the stand's fixture")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicsAlgorithmFixtureTest {

    private static final int D = TopicsFixture.DIMENSIONS;

    private TopicsFixture fixture;
    private IntentMerger.Merged merged;

    @BeforeAll
    void beforeAll() {
        fixture = TopicsFixture.load();
        merged = IntentMerger.merge(fixture.intents, fixture.vectors, D);
    }

    @Test
    @DisplayName("merge: unique texts, counts, first traces and order equal the stand's (an NBSP and an emoji included)")
    void merge() {
        var expected = fixture.expected;
        assertThat(merged.texts()).containsExactlyElementsOf(TopicsFixture.strings(expected.get("unique_texts")));
        assertThat(merged.weights()).containsExactly(TopicsFixture.ints(expected.get("unique_counts")));
        assertThat(merged.firstIndex()).containsExactly(TopicsFixture.ints(expected.get("unique_first")));
        assertThat(merged.texts()).anyMatch(text -> text.contains(" "));
        assertThat(merged.texts()).anyMatch(text -> text.contains("🚀"));
        for (int u = 0; u < merged.size(); u++) {
            var first = merged.firstIndex()[u];
            assertThat(Arrays.copyOfRange(merged.vectors(), u * D, (u + 1) * D))
                    .containsExactly(Arrays.copyOfRange(fixture.vectors, first * D, (first + 1) * D));
        }
        assertThat(fixture.k()).isEqualTo(Math.min(Math.min(50, Math.max(15, merged.size() / 60)), merged.size()));
    }

    /**
     * The bar compares distributions over 10 seeds, as both sides are seeded: the mean and the best weighted inertia
     * within 3% of scikit-learn's (each scikit-learn seed is already the best of 3 greedy k-means++ restarts, so one
     * Java seed against scikit-learn's best of 10 seeds would compare 3 tries with 30), and the mean agreement with
     * scikit-learn's seed 0 at or above scikit-learn's own lowest seed-to-seed agreement.
     */
    @Test
    @DisplayName("k-means: inertia within 3% of scikit-learn's, agreement at scikit-learn's own seed-to-seed level, no empty group, deterministic")
    void kMeansQuality() {
        var sklearn = fixture.expected.get("sklearn");
        var sklearnInertias = new ArrayList<Double>();
        sklearn.get("seeds").forEach(seed -> sklearnInertias.add(seed.get("inertia").asDouble()));
        int[] reference = TopicsFixture.ints(sklearn.get("seeds").get(0).get("labels"));
        double sklearnMinAri = sklearn.get("seed_ari_vs_seed0").get("min").asDouble();
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        int k = fixture.k();

        var inertias = new ArrayList<Double>();
        var aris = new ArrayList<Double>();
        for (long seed = 0; seed < 10; seed++) {
            var result = KMeans.fit(merged.vectors(), weights, D, k, seed);
            inertias.add(result.inertia());
            aris.add(adjustedRand(result.labels(), reference));
            assertThat(IntStream.of(result.labels()).distinct().count()).as("seed %s groups", seed).isEqualTo(k);
            var again = KMeans.fit(merged.vectors(), weights, D, k, seed);
            assertThat(again.labels()).containsExactly(result.labels());
            assertThat(again.centres()).containsExactly(result.centres());
            for (int j = 0; j < k; j++) {
                double norm = 0;
                for (int t = 0; t < D; t++) {
                    norm += (double) result.centres()[j * D + t] * result.centres()[j * D + t];
                }
                assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-5));
            }
        }
        assertThat(mean(inertias)).as("mean inertia, Java %s vs scikit-learn %s", inertias, sklearnInertias)
                .isLessThanOrEqualTo(mean(sklearnInertias) * 1.03);
        assertThat(min(inertias)).as("best inertia").isLessThanOrEqualTo(min(sklearnInertias) * 1.03);
        assertThat(mean(aris)).as("mean ARI to scikit-learn's seed 0, per Java seed %s", aris)
                .isGreaterThanOrEqualTo(sklearnMinAri);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    @Test
    @DisplayName("summaries and the author prompt equal the stand's byte for byte, given its cluster labels")
    void summariesAndPrompt() {
        var summaries = summaries();
        var expected = fixture.expected.get("summaries");
        assertThat(summaries).hasSize(expected.size());
        for (int j = 0; j < summaries.size(); j++) {
            var actual = summaries.get(j);
            var want = expected.get(j);
            assertThat(actual.group()).isEqualTo(want.get("cluster").asInt());
            assertThat(actual.size()).isEqualTo(want.get("size").asInt());
            assertThat(actual.share()).isEqualTo(want.get("share").asDouble());
            assertThat(actual.recentShare()).isEqualTo(want.get("recent_share").asDouble());
            assertThat(actual.tightness()).isCloseTo(want.get("coherence").asDouble(), within(1e-9));
            assertThat(actual.examples()).containsExactlyElementsOf(TopicsFixture.strings(want.get("examples")));
            assertThat(actual.uniqueTexts()).isEqualTo(want.get("n_unique").asInt());
        }
        assertThat(TopicPrompts.author(summaries, 10)).isEqualTo(fixture.authorPrompt);
    }

    @Test
    @DisplayName("apply and assignment: the topic map, the prototypes and the final labels equal the stand's")
    void applyAndAssign() throws IOException {
        var summaries = summaries();
        var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree(fixture.answer.toString()));
        var applied = AuthorApplier.apply(answer, summaries, 10);

        var expectedTopics = fixture.expected.get("topics");
        assertThat(applied.topics()).hasSize(expectedTopics.size());
        for (int t = 0; t < expectedTopics.size(); t++) {
            assertThat(applied.topics().get(t).name()).isEqualTo(expectedTopics.get(t).get("name").asText());
            assertThat(applied.topics().get(t).definition())
                    .isEqualTo(expectedTopics.get(t).get("definition").asText());
            assertThat(applied.topics().get(t).groups())
                    .containsExactly(boxed(TopicsFixture.ints(expectedTopics.get(t).get("clusters"))));
        }
        assertThat(applied.other()).containsExactly(boxed(TopicsFixture.ints(fixture.expected.get("other"))));

        var topicVectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t = 0; t < applied.topics().size(); t++) {
            for (int j : applied.topics().get(t).groups()) {
                topicVectors.add(centre(j));
                owners.add(t);
            }
        }
        var otherVectors = applied.other().stream().map(this::centre).toList();
        var assigner = new PrototypeAssigner(topicVectors, owners, otherVectors, D);
        var prototypes = fixture.expected.get("prototypes");
        assertThat(assigner.gatedOther()).isEqualTo(prototypes.get("gated_other").size());
        assertThat(assigner.prototypeCount()).isEqualTo(
                prototypes.get("topic_prototypes").size() + prototypes.get("other_prototypes").size());

        assertThat(assigner.assign(fixture.vectors))
                .containsExactly(TopicsFixture.ints(fixture.expected.get("final_labels")));
    }

    private List<GroupSummaries.GroupSummary> summaries() {
        return GroupSummaries.summarize(fixture.intents, fixture.vectors, fixture.sklearnTraceLabels(merged),
                fixture.centres, fixture.recent(), D);
    }

    private float[] centre(int j) {
        return Arrays.copyOfRange(fixture.centres, j * D, (j + 1) * D);
    }

    private static Integer[] boxed(int[] values) {
        return Arrays.stream(values).boxed().toArray(Integer[]::new);
    }

    /** Hubert and Arabie's adjusted Rand index, as scikit-learn's {@code adjusted_rand_score}. */
    static double adjustedRand(int[] a, int[] b) {
        var pairs = new HashMap<Long, Long>();
        var rows = new HashMap<Integer, Long>();
        var cols = new HashMap<Integer, Long>();
        for (int i = 0; i < a.length; i++) {
            pairs.merge(((long) a[i] << 32) | (b[i] & 0xffffffffL), 1L, Long::sum);
            rows.merge(a[i], 1L, Long::sum);
            cols.merge(b[i], 1L, Long::sum);
        }
        double index = pairs.values().stream().mapToDouble(TopicsAlgorithmFixtureTest::choose2).sum();
        double rowSum = rows.values().stream().mapToDouble(TopicsAlgorithmFixtureTest::choose2).sum();
        double colSum = cols.values().stream().mapToDouble(TopicsAlgorithmFixtureTest::choose2).sum();
        double expected = rowSum * colSum / choose2(a.length);
        double max = (rowSum + colSum) / 2;
        return max == expected ? 1.0 : (index - expected) / (max - expected);
    }

    private static double choose2(long n) {
        return n * (n - 1) / 2.0;
    }
}
