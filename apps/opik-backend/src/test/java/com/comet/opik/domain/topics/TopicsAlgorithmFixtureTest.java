package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.InferenceJson;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * The Java first build against the fixture's expected outputs. The deterministic parts are equal byte for byte given
 * the fixture's cluster labels; the k-means passes the quality bar.
 */
@DisplayName("topics first build: the algorithm against the fixture")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicsAlgorithmFixtureTest {

    private static final int D = TopicsFixture.DIMENSIONS;
    private static final TopicsConfig.AlgorithmConfig CONFIG = TopicsTestAlgorithm.config();
    private static final String WORKSPACE_ID = RandomStringUtils.secure().nextAlphanumeric(10);
    private static final UUID PROJECT_ID = TopicsTestAlgorithm.ID_GENERATOR.generateId();
    /** The first build at the fixture's 15 groups: config.yml's bounds would make 30 of its 257 texts. */
    private static final KMeansTopicAlgorithm FIRST_BUILD = TopicsTestAlgorithm
            .algorithm(TopicsTestAlgorithm.fixedGroups(15));

    private TopicsFixture fixture;
    private MergedIntents merged;

    @BeforeAll
    void beforeAll() {
        fixture = TopicsFixture.load();
        merged = IntentMerger.merge(fixture.intents, fixture.vectors, D);
    }

    @Test
    @DisplayName("merge: unique texts, counts, first traces and order equal the fixture's (an NBSP and an emoji included)")
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
        // The fixture groups this window into 15; the first build that runs this algorithm makes finer groups.
        assertThat(fixture.k()).isEqualTo(15);
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
        int k = fixture.k();

        var inertias = new ArrayList<Double>();
        var aris = new ArrayList<Double>();
        for (long seed = 0; seed < 10; seed++) {
            var result = fit(seed);
            inertias.add(result.inertia());
            aris.add(adjustedRand(result.labels(), reference));
            assertThat(IntStream.of(result.labels()).distinct().count()).as("seed %s groups", seed).isEqualTo(k);
            var again = fit(seed);
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

    /** The backend's k-means of the fixture window at the fixture's k. */
    private KMeansResult fit(long seed) {
        return KMeans.fit(merged.vectors(), Arrays.stream(merged.weights()).asDoubleStream().toArray(), D, fixture.k(),
                seed, CONFIG.getKmeansMaxIterations(), CONFIG.getKmeansRestarts());
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    @Test
    @DisplayName("summaries equal the fixture's byte for byte, given its cluster labels; the prompt lists every group")
    void summariesAndPrompt() {
        var summaries = summaries();
        var expected = fixture.expected.get("summaries");
        // The fixture's summaries list up to 3 repeated texts, then the nearest ones, 5 in all.
        var labels = TopicsFixture.ints(fixture.expected.get("sklearn").get("labels"));
        var standExamples = GroupSummaries.authorExamples(merged, labels, fixture.centres, 3, 2);
        assertThat(summaries).hasSize(expected.size());
        for (int j = 0; j < summaries.size(); j++) {
            var actual = summaries.get(j);
            var want = expected.get(j);
            assertThat(actual.group()).isEqualTo(want.get("cluster").asInt());
            assertThat(actual.size()).isEqualTo(want.get("size").asInt());
            assertThat(actual.share()).isEqualTo(want.get("share").asDouble());
            assertThat(standExamples.get(j)).containsExactlyElementsOf(TopicsFixture.strings(want.get("examples")));
        }
        var examples = GroupSummaries.authorExamples(merged, labels, fixture.centres, 5, 5);
        var prompt = TopicPrompts.author(new TopicTasks().author(), summaries, 10, examples);
        for (var summary : summaries) {
            assertThat(prompt).contains("  cluster " + summary.group() + ": "
                    + Math.round(100 * summary.share()) + "% of traffic\n    - "
                    + examples.get(summary.group()).getFirst());
        }
    }

    @Test
    @DisplayName("apply and assignment: the topic map, the prototypes and the final labels equal the fixture's")
    void applyAndAssign() throws IOException {
        var summaries = summaries();
        var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree(fixture.answer.toString()));
        var applied = AuthorApplier.apply(answer, summaries, 10);

        var expectedTopics = fixture.expected.get("topics");
        var names = new ArrayList<String>();
        expectedTopics.forEach(topic -> names.add(topic.get("name").asText()));
        assertThat(applied.topics()).extracting(AppliedTopic::name)
                .containsExactlyInAnyOrderElementsOf(names);
        for (var topic : applied.topics()) {
            var want = expectedTopics.get(names.indexOf(topic.name()));
            assertThat(topic.definition()).isEqualTo(want.get("definition").asText());
            assertThat(topic.groups()).containsExactlyInAnyOrder(boxed(TopicsFixture.ints(want.get("clusters"))));
        }
        assertThat(applied.other()).containsExactlyInAnyOrder(boxed(TopicsFixture.ints(fixture.expected.get("other"))));

        var topicVectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t = 0; t < expectedTopics.size(); t++) {
            for (int j : TopicsFixture.ints(expectedTopics.get(t).get("clusters"))) {
                topicVectors.add(centre(j));
                owners.add(t);
            }
        }
        var otherVectors = Arrays.stream(TopicsFixture.ints(fixture.expected.get("other"))).mapToObj(this::centre)
                .toList();
        var assigner = new PrototypeAssigner(topicVectors, owners, otherVectors, D, CONFIG.getAssignMinCosine(),
                CONFIG.getOtherGate());
        var prototypes = fixture.expected.get("prototypes");
        assertThat(assigner.gatedOther()).isEqualTo(prototypes.get("gated_other").size());
        assertThat(topicVectors.size() + assigner.keptOther().size()).isEqualTo(
                prototypes.get("topic_prototypes").size() + prototypes.get("other_prototypes").size());

        assertThat(assigner.assign(fixture.vectors))
                .containsExactly(TopicsFixture.ints(fixture.expected.get("final_labels")));
    }

    /**
     * The whole first build, with the recorded answer moved onto the backend's grouping. The reference cannot run the
     * backend's k-means, so it builds on the grouping in {@code java_kmeans.json}; the first check pins that grouping.
     */
    @Test
    @DisplayName("firstBuild: the topics, prototypes, owners and labels equal the reference on the backend's grouping")
    void firstBuild() {
        var groups = fit(KMeansTopicAlgorithm.SEED).labels();
        assertThat(groups)
                .as("k-means changed: put %s in java_kmeans.json and run generate.py", Arrays.toString(groups))
                .containsExactly(fixture.javaGroups);
        var clustering = FIRST_BUILD.cluster(window(), 10);

        var build = clustering.finish(AuthorAnswer.parse(fixture.firstBuildAnswer));

        var want = fixture.expected.get("first_build");
        var prototypes = want.get("prototypes");
        assertThat(clustering.stats().get("k").asInt()).isEqualTo(fixture.k());
        var names = new ArrayList<Tuple>();
        want.get("topics").forEach(topic -> names.add(tuple(topic.get("name").asText(),
                topic.get("definition").asText())));
        assertThat(build.topics()).extracting(NewTopic::name, NewTopic::definition).containsExactlyElementsOf(names);
        // The reference lists the prototypes flat, each with the index of its topic.
        var owners = new ArrayList<Integer>();
        for (int t = 0; t < build.topics().size(); t++) {
            owners.addAll(Collections.nCopies(build.topics().get(t).prototypes().size(), t));
        }
        assertThat(owners).containsExactly(boxed(TopicsFixture.ints(prototypes.get("owners"))));
        assertCentres(build.topics().stream().flatMap(topic -> topic.prototypes().stream()).toList(),
                TopicsFixture.ints(prototypes.get("topic_prototypes")));
        assertCentres(build.otherPrototypes(), TopicsFixture.ints(prototypes.get("other_prototypes")));
        assertThat(build.labels()).containsExactly(TopicsFixture.ints(want.get("labels")));
    }

    @Test
    @DisplayName("the labeller over a saved first build labels the window as the build did")
    void labellerMatchesFirstBuild() {
        var build = FIRST_BUILD.cluster(window(), 10).finish(AuthorAnswer.parse(fixture.firstBuildAnswer));
        var topics = IntStream.range(0, build.topics().size())
                .mapToObj(t -> TopicsTestAlgorithm.topic(WORKSPACE_ID, PROJECT_ID)
                        .name(build.topics().get(t).name())
                        .definition(build.topics().get(t).definition())
                        .vectors(new PrototypeVectors(build.topics().get(t).prototypes()))
                        .build())
                .toList();
        assertThat(build.otherPrototypes()).isNotEmpty();

        var labels = FIRST_BUILD.labeller(topics, new PrototypeVectors(build.otherPrototypes())).orElseThrow()
                .label(fixture.vectors);

        assertThat(labels).containsExactly(Arrays.stream(build.labels())
                .mapToObj(label -> label == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topics.get(label).id())
                .toArray(UUID[]::new));
    }

    private TopicWindow window() {
        return TopicWindow.builder().texts(fixture.intents).vectors(fixture.vectors).build();
    }

    /** Each vector against the reference's centre of its group, float32 against float64 arithmetic. */
    private void assertCentres(List<float[]> actual, int[] groups) {
        assertThat(actual).hasSize(groups.length);
        for (int i = 0; i < groups.length; i++) {
            assertThat(actual.get(i)).as("prototype %s, group %s", i, groups[i]).containsExactly(
                    Arrays.copyOfRange(fixture.firstBuildCentres, groups[i] * D, (groups[i] + 1) * D),
                    within(1e-6f));
        }
    }

    private List<GroupSummary> summaries() {
        return GroupSummaries.summarize(fixture.sklearnTraceLabels(merged), fixture.centres.length / D);
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
