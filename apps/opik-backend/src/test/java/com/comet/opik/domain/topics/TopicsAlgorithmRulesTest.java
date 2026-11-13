package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The small rules of the first build: number formats, text order and strip, the answer, apply and assignment. */
@DisplayName("topics first build: rules")
class TopicsAlgorithmRulesTest {

    @Nested
    @DisplayName("text rules")
    class Text {

        @ParameterizedTest(name = "{0} with {1} places is {2}")
        @CsvSource({
                "0.25, 1, 0.2",
                "0.35, 1, 0.3",
                "0.45, 1, 0.5",
                "2.5, 0, 2",
                "3.5, 0, 4",
                "0.125, 2, 0.12",
                "-0.001, 2, -0.00",
                "30.5, 1, 30.5",
                "0.9449999999999999, 2, 0.94"})
        @DisplayName("fixed equals Python's f-string format")
        void fixed(double value, int places, String expected) {
            assertThat(TopicText.fixed(value, places)).isEqualTo(expected);
        }

        @Test
        @DisplayName("shares format as 100 * size / n, rounded as Python's f-format")
        void shares() {
            assertThat(TopicText.fixed(100 * ((double) 122 / 400), 1)).isEqualTo("30.5");
            assertThat(TopicText.fixed(100 * ((double) 1 / 3), 1)).isEqualTo("33.3");
            assertThat(TopicText.fixed(100 * ((double) 1 / 8), 1)).isEqualTo("12.5");
        }

        @Test
        @DisplayName("code point order puts an emoji after U+FFFD, unlike String.compareTo")
        void codePointOrder() {
            var emoji = "🚀";
            var replacement = "�";
            assertThat(emoji.compareTo(replacement)).isNegative();
            assertThat(TopicText.compareCodePoints(emoji, replacement)).isPositive();
            assertThat(TopicText.compareCodePoints("ab", "abc")).isNegative();
            assertThat(TopicText.compareCodePoints("abc", "abc")).isZero();
        }

        @Test
        @DisplayName("pythonStrip removes no-break spaces, which String.strip keeps")
        void strip() {
            assertThat(TopicText.pythonStrip("  Daily Reports　\n")).isEqualTo("Daily Reports");
            assertThat(" x".strip()).isEqualTo(" x");
            assertThat(TopicText.pythonStrip("a b")).isEqualTo("a b");
            assertThat(TopicText.pythonStrip(" \t ")).isEmpty();
        }

        @ParameterizedTest(name = "{0} is a catch-all as a whole: {1}")
        @CsvSource({
                "Other, true",
                "OTHERS, true",
                "' Miscellaneous Requests ', true",
                "'Other  Requests', true",
                "'Other\tRequests', true",
                "'General\u00A0Questions', true",
                "General Questions, true",
                "Various Tasks, true",
                "Mixed, true",
                "Mixed Requests, true",
                "Mixed Martial Arts, false",
                "General Job Search, false",
                "Other and System Events, false",
                "Generalization Help, false",
                "Weekly Sales Summaries, false"})
        @DisplayName("catch-all names: the author's rule drops a name only when it is a catch-all as a whole")
        void catchAllName(String name, boolean expected) {
            assertThat(AuthorApplier.isCatchAllName(name)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("the author answer")
    class Answer {

        @Test
        @DisplayName("an object parses as is, unknown keys ignored")
        void object() throws IOException {
            var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree(
                    "{\"topics\": [], \"assignment\": [], \"note\": 1}"));
            assertThat(answer.topics()).isEmpty();
        }

        @Test
        @DisplayName("each cluster goes to the topic of its first mention; -1 and an index off the list end in Other")
        void perCluster() throws IOException {
            var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree("{\"topics\": [{\"name\": \"A\", "
                    + "\"definition\": \"a\"}, {\"name\": \"B\", \"definition\": \"b\"}], \"assignment\": ["
                    + "{\"cluster\": 0, \"topic\": 1}, {\"cluster\": 1, \"topic\": 0}, {\"cluster\": 0, \"topic\": 0}, "
                    + "{\"cluster\": 2, \"topic\": -1}, {\"cluster\": 3, \"topic\": 7}]}"));

            assertThat(answer.topics()).extracting(AuthorAnswer.Topic::name).containsExactly("A", "B");
            assertThat(answer.topics().get(0).clusters()).containsExactly(1);
            assertThat(answer.topics().get(1).clusters()).containsExactly(0);
            var groups = IntStream.range(0, 4)
                    .mapToObj(j -> GroupSummary.builder().group(j).size(10).examples(List.of()).build()).toList();
            assertThat(AuthorApplier.apply(answer, groups, 10).other()).containsExactly(2, 3);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "{\"topics\": []}",
                "{\"assignment\": []}",
                "{\"topics\": [{\"name\": \"A\"}], \"assignment\": []}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\"}], \"assignment\": [{\"cluster\": 1.5, \"topic\": 0}]}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\"}], \"assignment\": [{\"cluster\": 1}]}",
                "{\"topics\": [{\"name\": 3, \"definition\": \"d\"}], \"assignment\": []}",
                "[1, 2]"})
        @DisplayName("an answer off the schema is qwen_bad_answer")
        void bad(String json) throws IOException {
            var node = InferenceJson.MAPPER.readTree(json);
            assertThatThrownBy(() -> AuthorAnswer.parse(node))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_BAD_ANSWER));
        }

        @Test
        @DisplayName("a textual output is qwen_bad_answer, even one holding a valid answer; the error keeps no text")
        void textIsBad() {
            var text = "{\"topics\": [{\"name\": \"user-secret-text\", \"definition\": \"d\"}], \"assignment\": []}";
            assertThatThrownBy(() -> AuthorAnswer.parse(TextNode.valueOf(text)))
                    .isInstanceOfSatisfying(TopicsRunException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo(Code.QWEN_BAD_ANSWER);
                        assertThat(exception.getCause()).isNull();
                        assertThat(exception.getMessage()).doesNotContain("user-secret-text");
                    });
            assertThatThrownBy(() -> AuthorAnswer.parse(null))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_BAD_ANSWER));
        }
    }

    @Nested
    @DisplayName("the author prompt")
    class Prompt {

        @Test
        @DisplayName("examples: the repeated texts, then the ones nearest the centre, no text twice")
        void examples() {
            String[] texts = {"a", "a", "a", "b", "c", "d", "e"};
            float[] vectors = {1, 0, 1, 0, 1, 0, 0.99f, 0.141f, 0.95f, 0.312f, 0.8f, 0.6f, 0.6f, 0.8f};
            int[] labels = {0, 0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0, 0, 1};

            var examples = GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 2, 2);

            assertThat(examples).hasSize(2);
            assertThat(examples.get(0)).containsExactly("a", "b", "c", "d");
            assertThat(examples.get(1)).isEmpty();
            assertThat(GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 5, 0).get(0))
                    .containsExactly("a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("examples, no text repeats: all are the nearest ones, ties by trace order, not code point order")
        void examplesWithoutRepeats() {
            String[] texts = {"b", "a", "d", "c", "e"};
            float[] vectors = {1, 0, 1, 0, 0.8f, 0.6f, 0.6f, 0.8f, 0, 1};
            int[] labels = {0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 2, 1).get(0))
                    .containsExactly("b", "a", "d");
        }

        @Test
        @DisplayName("examples, some texts repeat: the repeated ones first, the free slots go to the nearest ones")
        void examplesWithSomeRepeats() {
            String[] texts = {"z", "a", "z", "d", "c", "b"};
            float[] vectors = {0, 1, 0.6f, 0.8f, 0, 1, 1, 0, 0.95f, 0.312f, 0.8f, 0.6f};
            int[] labels = {0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 3, 1).get(0))
                    .containsExactly("z", "d", "c", "b");
        }

        @Test
        @DisplayName("examples, more repeated texts than slots: most frequent first, ties by code point order")
        void examplesWithMoreRepeatsThanSlots() {
            String[] texts = {"y", "x", "y", "w", "x", "y", "w", "v"};
            float[] vectors = {0, 1, 0.8f, 0.6f, 0, 1, 0.6f, 0.8f, 0.8f, 0.6f, 0, 1, 0.6f, 0.8f, 1, 0};
            int[] labels = {0, 0, 0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 2, 1).get(0))
                    .containsExactly("y", "w", "v");
            assertThat(GroupSummaries.authorExamples(texts, vectors, labels, centres, 2, 2, 2).get(0))
                    .containsExactly("y", "w", "v", "x");
        }

        @Test
        @DisplayName("the prompt lists the groups largest first with a whole-percent share and the given examples")
        void prompt() {
            var groups = List.of(
                    GroupSummary.builder().group(0).size(10).share(0.1).examples(List.of("own")).build(),
                    GroupSummary.builder().group(1).size(40).share(0.4).examples(List.of()).build(),
                    GroupSummary.builder().group(2).size(0).examples(List.of()).build());

            var prompt = TopicPrompts.author(groups, 10, List.of(List.of("zero"), List.of("one", "uno"), List.of()));

            assertThat(prompt)
                    .contains("at most 10 topics")
                    .contains(
                            "  cluster 1: 40% of traffic\n    - one\n    - uno\n  cluster 0: 10% of traffic\n    - zero")
                    .contains("\"assignment\"")
                    .doesNotContain("cluster 2:", "- own", "tightness", "{clusters}");
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        private final List<GroupSummary> groups = IntStream.range(0, 14)
                .mapToObj(j -> GroupSummary.builder().group(j).size(100 - j).examples(List.of()).build())
                .toList();

        @Test
        @DisplayName("11 topics keep the 10 largest; catch-alls are dropped before the cut")
        void cap() {
            var topics = new ArrayList<AuthorAnswer.Topic>();
            topics.add(new AuthorAnswer.Topic("Other Stuff", "d", List.of(0)));
            for (int j = 1; j <= 11; j++) {
                topics.add(new AuthorAnswer.Topic("Topic " + j, "d", List.of(j)));
            }
            var applied = AuthorApplier.apply(new AuthorAnswer(topics), groups, 10);
            assertThat(applied.topics()).extracting(AuthorApplier.NewTopic::name)
                    .containsExactly("Topic 1", "Topic 2", "Topic 3", "Topic 4", "Topic 5", "Topic 6", "Topic 7",
                            "Topic 8", "Topic 9", "Topic 10");
            assertThat(applied.other()).containsExactly(0, 11, 12, 13);
        }

        @Test
        @DisplayName("a group goes to the largest topic that lists it; invalid and repeated ids are ignored")
        void claims() {
            var answer = new AuthorAnswer(List.of(
                    new AuthorAnswer.Topic(" Small ", " d ", List.of(5, 99, -1)),
                    new AuthorAnswer.Topic("Big", "d", List.of(1, 2, 2, 5)),
                    new AuthorAnswer.Topic("Empty", "d", List.of(1))));
            var applied = AuthorApplier.apply(answer, groups, 10);
            assertThat(applied.topics()).hasSize(1);
            assertThat(applied.topics().getFirst().name()).isEqualTo("Big");
            assertThat(applied.topics().getFirst().groups()).containsExactly(1, 2, 5);
            assertThat(applied.other()).containsExactly(0, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13);
        }

        @Test
        @DisplayName("names and definitions are stripped as Python strips them; ties keep the answer's order")
        void strip() {
            var answer = new AuthorAnswer(List.of(
                    new AuthorAnswer.Topic(" B ", " b ", List.of(3)),
                    new AuthorAnswer.Topic("A", "a", List.of(4, -5))));
            var flat = IntStream.range(0, 6)
                    .mapToObj(j -> GroupSummary.builder().group(j).size(10).examples(List.of()).build()).toList();
            var applied = AuthorApplier.apply(answer, flat, 10);
            assertThat(applied.topics()).extracting(AuthorApplier.NewTopic::name).containsExactly("B", "A");
            assertThat(applied.topics().getFirst().definition()).isEqualTo("b");
        }

        @Test
        @DisplayName("topics with the same name merge into the largest one, at the first one's place")
        void sameNames() {
            var answer = new AuthorAnswer(List.of(
                    new AuthorAnswer.Topic("Email Drafting", "small", List.of(9, 4)),
                    new AuthorAnswer.Topic("Billing", "d", List.of(2)),
                    new AuthorAnswer.Topic(" email\u00A0 drafting", "large", List.of(3, 4)),
                    new AuthorAnswer.Topic("Email Drafts", "d", List.of(10))));
            var applied = AuthorApplier.apply(answer, groups, 10);
            assertThat(applied.topics()).extracting(AuthorApplier.NewTopic::name)
                    .containsExactly("email\u00A0 drafting", "Billing", "Email Drafts");
            var email = applied.topics().getFirst();
            assertThat(email.definition()).isEqualTo("large");
            assertThat(email.groups()).containsExactly(3, 4, 9);
        }

        @Test
        @DisplayName("a blank name is no name: its groups go to Other and it takes no slot")
        void blankName() {
            var answer = new AuthorAnswer(List.of(
                    new AuthorAnswer.Topic(" \u00A0 ", "d", List.of(1)),
                    new AuthorAnswer.Topic("Billing", "d", List.of(2))));
            var applied = AuthorApplier.apply(answer, groups, 1);
            assertThat(applied.topics()).extracting(AuthorApplier.NewTopic::name).containsExactly("Billing");
            assertThat(applied.other()).contains(1).doesNotContain(2);
        }

        @Test
        @DisplayName("a name over 255 code points is cut to 255, so the save can't fail on it")
        void longName() {
            var name = "\uD83D\uDE00".repeat(300);
            var answer = new AuthorAnswer(List.of(new AuthorAnswer.Topic(name, "d", List.of(1))));
            var stored = AuthorApplier.apply(answer, groups, 10).topics().getFirst().name();
            assertThat(stored.codePointCount(0, stored.length())).isEqualTo(TopicText.MAX_NAME_LENGTH);
            assertThat(TopicText.name(" Billing ")).isEqualTo("Billing");
        }

        @Test
        @DisplayName("the name key strips, collapses whitespace as Python does, and lower-cases")
        void nameKey() {
            assertThat(AuthorApplier.nameKey("\u00A0 Email \t\u2003Drafting \n")).isEqualTo("email drafting");
            assertThat(AuthorApplier.nameKey("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("assignment")
    class Assignment {

        @Test
        @DisplayName("ties go to the lower prototype; below 0.75 is Other")
        void ties() {
            var a = new float[]{1, 0};
            var assigner = new PrototypeAssigner(List.of(a, a.clone()), List.of(0, 1), List.of(), 2);
            assertThat(assigner.assign(new float[]{1, 0, 0.7f, 0.71414284f})).containsExactly(0,
                    PrototypeAssigner.OTHER);
        }

        @Test
        @DisplayName("the winner: ties to the earlier prototype, a gated known-Other one never wins, below 0.75 none")
        void winners() {
            var a = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var assigner = new PrototypeAssigner(List.of(a, a.clone()), List.of(0, 1), List.of(close, far), 2);

            assertThat(assigner.winners(new float[]{1, 0, 0.96f, 0.28f, 0, 1, 0.6f, -0.8f}))
                    .containsExactly(0, 0, 2, PrototypeAssigner.NO_WINNER);
        }

        @Test
        @DisplayName("a known-Other prototype at 0.95 or more to a topic prototype is left out")
        void gate() {
            var topic = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var assigner = new PrototypeAssigner(List.of(topic), List.of(0), List.of(close, far), 2);
            assertThat(assigner.gatedOther()).isEqualTo(1);
            assertThat(assigner.prototypeCount()).isEqualTo(2);
            assertThat(assigner.assign(new float[]{0.96f, 0.28f, 0, 1})).containsExactly(0, PrototypeAssigner.OTHER);
        }

        @Test
        @DisplayName("the kept Other prototypes alone assign as all of them do, and none is within 0.95 of a topic")
        void keptOther() {
            var topic = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var all = new PrototypeAssigner(List.of(topic), List.of(0), List.of(close, far), 2);

            assertThat(all.keptOther()).containsExactly(far);
            for (var other : all.keptOther()) {
                assertThat((double) other[0] * topic[0] + (double) other[1] * topic[1])
                        .isLessThan(PrototypeAssigner.OTHER_GATE);
            }
            var kept = new PrototypeAssigner(List.of(topic), List.of(0), all.keptOther(), 2);
            assertThat(kept.gatedOther()).isZero();
            var vectors = new float[2 * 360];
            for (int d = 0; d < 360; d++) {
                vectors[2 * d] = (float) Math.cos(Math.toRadians(d));
                vectors[2 * d + 1] = (float) Math.sin(Math.toRadians(d));
            }
            assertThat(kept.assign(vectors)).containsExactly(all.assign(vectors));
        }
    }

    @Nested
    @DisplayName("k-means edge cases")
    class KMeansEdges {

        @ParameterizedTest
        @MethodSource("invalidInputCases")
        @DisplayName("rejects a non-positive dimension count and a weight that is not finite and positive")
        void invalidInputs(int dimensions, double weight, String message) {
            var points = new float[]{1, 0, 0, 1};
            assertThatThrownBy(() -> KMeans.fit(points, new double[]{1, weight}, dimensions, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(message);
        }

        static Stream<Arguments> invalidInputCases() {
            return Stream.of(
                    Arguments.of(0, 1, "dimensions must be positive"),
                    Arguments.of(-2, 1, "dimensions must be positive"),
                    Arguments.of(2, 0, "weights must be finite and positive"),
                    Arguments.of(2, -1, "weights must be finite and positive"),
                    Arguments.of(2, Double.NaN, "weights must be finite and positive"),
                    Arguments.of(2, Double.POSITIVE_INFINITY, "weights must be finite and positive"),
                    Arguments.of(2, Double.NEGATIVE_INFINITY, "weights must be finite and positive"));
        }

        @Test
        @DisplayName("k equal to the number of points: duplicate points share a group, every other point has its own")
        void kEqualsN() {
            var points = new float[]{1, 0, 0, 1, 1, 0, 0.6f, 0.8f};
            var result = KMeans.fit(points, new double[]{1, 1, 1, 1}, 2, 4, 0);
            var labels = result.labels();
            assertThat(labels).hasSize(4);
            assertThat(labels[0]).isEqualTo(labels[2]);
            assertThat(IntStream.of(labels[0], labels[1], labels[3]).distinct().count()).isEqualTo(3);
        }

        @Test
        @DisplayName("an interrupted thread stops the fit")
        void interrupted() {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> KMeans.fit(new float[]{1, 0, 0, 1}, new double[]{1, 1}, 2, 2, 0))
                        .isInstanceOf(java.util.concurrent.CancellationException.class);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("k-means with Elkan's bounds gives plain Lloyd's labels")
    class KMeansSameLabels {

        @ParameterizedTest(name = "n {0}, {1} dims, {2} clusters, noise {3}, k {4}, seed {5}, capped {6}")
        @CsvSource({
                "1500, 384, 40, 0.15, 50, 0, false",
                "1500, 384, 40, 0.15, 50, 1, false",
                "2000, 13, 10, 0.5, 30, 0, false",
                // 20 distinct points, each repeated: k above that leaves clusters empty, reseeded on a duplicate.
                "300, 16, 20, 0, 30, 0, false",
                // Random directions: the fit stops at MAX_ITERATIONS.
                "30000, 8, 30000, 10, 30, 0, true"})
        @DisplayName("same labels, iterations and centres as the plain fit on a fixed synthetic set")
        void samePlainFit(int n, int dimensions, int clusters, double noise, int k, long seed, boolean capped) {
            var points = syntheticPoints(n, dimensions, clusters, noise);
            var random = new SplittableRandom(13);
            var weights = new double[n];
            for (int i = 0; i < n; i++) {
                weights[i] = 1 + random.nextInt(4);
            }

            var plain = PlainKMeans.fit(points, weights, dimensions, k, seed);
            var fast = KMeans.fit(points, weights, dimensions, k, seed);

            // The capped row covers the stop at MAX_ITERATIONS, the others a converged fit.
            assertThat(plain.iterations() == KMeans.MAX_ITERATIONS).as("stops at MAX_ITERATIONS").isEqualTo(capped);
            assertThat(fast.labels()).containsExactly(plain.labels());
            assertThat(fast.iterations()).isEqualTo(plain.iterations());
            assertThat(fast.centres()).containsExactly(plain.centres(), within(1e-6f));
            // Relative, or absolute near 0: plain Lloyd scores duplicates on their centres exactly 0, the dot form ~1e-14.
            assertThat(fast.inertia()).isCloseTo(plain.inertia(), within(1e-9 * Math.max(1, plain.inertia())));
        }

        /** Unit centres from gaussians, each point a centre plus gaussian noise, normalized; seeded, so fixed. */
        private static float[] syntheticPoints(int n, int dimensions, int clusters, double noise) {
            var random = new SplittableRandom(11);
            var centres = new double[clusters * dimensions];
            for (int c = 0; c < clusters; c++) {
                double norm = 0;
                for (int t = 0; t < dimensions; t++) {
                    centres[c * dimensions + t] = random.nextGaussian();
                    norm += centres[c * dimensions + t] * centres[c * dimensions + t];
                }
                norm = Math.sqrt(norm);
                for (int t = 0; t < dimensions; t++) {
                    centres[c * dimensions + t] /= norm;
                }
            }
            var points = new float[n * dimensions];
            var row = new double[dimensions];
            for (int i = 0; i < n; i++) {
                int c = random.nextInt(clusters);
                double norm = 0;
                for (int t = 0; t < dimensions; t++) {
                    row[t] = centres[c * dimensions + t] + random.nextGaussian() * noise;
                    norm += row[t] * row[t];
                }
                norm = Math.sqrt(norm);
                for (int t = 0; t < dimensions; t++) {
                    points[i * dimensions + t] = (float) (row[t] / norm);
                }
            }
            return points;
        }
    }
}
