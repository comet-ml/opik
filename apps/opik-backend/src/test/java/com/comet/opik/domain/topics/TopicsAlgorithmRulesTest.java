package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.node.TextNode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The small rules of the discovery run: number formats, text order and strip, the answer, apply and assignment. */
@DisplayName("topics discovery run: rules")
class TopicsAlgorithmRulesTest {

    private static final int MAX_ITERATIONS = 150;
    private static final double OTHER_GATE = 0.95;
    private static final int RESTARTS = 3;

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
            var groups = List.of(GroupSummary.builder().group(0).size(1).build());
            var answer = authorAnswer(List.of(new AuthorTopic(name, "d", List.of(0))));

            var applied = AuthorApplier.apply(answer, groups, 10);

            assertThat(applied.topics()).hasSize(expected ? 0 : 1);
            assertThat(applied.other()).hasSize(expected ? 1 : 0);
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
        @DisplayName("each cluster goes to the topic of its name at its first mention; \"-1\" and a name off the list end "
                + "in Other, and only the name off the list counts as unmatched")
        void perCluster() throws IOException {
            var answer = AuthorAnswer
                    .parse(InferenceJson.MAPPER.readTree("{\"topics\": [{\"name\": \"Alpha Requests\", "
                            + "\"definition\": \"a\"}, {\"name\": \"Beta Requests\", \"definition\": \"b\"}], \"assignment\": ["
                            + "{\"cluster\": 0, \"topic\": \"Beta Requests\"}, {\"cluster\": 1, \"topic\": \"alpha  requests\"}, "
                            + "{\"cluster\": 0, \"topic\": \"Alpha Requests\"}, {\"cluster\": 2, \"topic\": \"-1\"}, "
                            + "{\"cluster\": 3, \"topic\": \"Gamma Requests\"}]}"));

            assertThat(answer.topics()).extracting(AuthorTopic::name).containsExactly("Alpha Requests",
                    "Beta Requests");
            assertThat(answer.topics().get(0).clusters()).containsExactly(1);
            assertThat(answer.topics().get(1).clusters()).containsExactly(0);
            assertThat(answer.unmatched()).isEqualTo(1);
            var groups = IntStream.range(0, 4).mapToObj(j -> GroupSummary.builder().group(j).size(10).build())
                    .toList();
            assertThat(AuthorApplier.apply(answer, groups, 10).other()).containsExactly(2, 3);
        }

        @Test
        @DisplayName("a topic named \"-1\" takes no cluster the answer sent to Other")
        void otherBeforeName() throws IOException {
            var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree("{\"topics\": [{\"name\": \" -1 \", "
                    + "\"definition\": \"d\"}], \"assignment\": [{\"cluster\": 0, \"topic\": \"-1\"}]}"));

            assertThat(answer.topics().getFirst().clusters()).isEmpty();
            assertThat(answer.unmatched()).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "{\"topics\": []}",
                "{\"assignment\": []}",
                "{\"topics\": [{\"name\": \"A\"}], \"assignment\": []}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\"}], \"assignment\": [{\"cluster\": 1.5, \"topic\": \"A\"}]}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\"}], \"assignment\": [{\"cluster\": 1, \"topic\": 0}]}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\"}], \"assignment\": [{\"cluster\": 1}]}",
                "{\"topics\": [{\"name\": 3, \"definition\": \"d\"}], \"assignment\": []}",
                "[1, 2]"})
        @DisplayName("an answer off the schema is llm_bad_answer")
        void bad(String json) throws IOException {
            var node = InferenceJson.MAPPER.readTree(json);
            assertThatThrownBy(() -> AuthorAnswer.parse(node))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(RunError.LLM_BAD_ANSWER));
        }

        @Test
        @DisplayName("a textual output is llm_bad_answer, even one holding a valid answer; the error keeps no text")
        void textIsBad() {
            var text = "{\"topics\": [{\"name\": \"user-secret-text\", \"definition\": \"d\"}], \"assignment\": []}";
            assertThatThrownBy(() -> AuthorAnswer.parse(TextNode.valueOf(text)))
                    .isInstanceOfSatisfying(TopicsRunException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo(RunError.LLM_BAD_ANSWER);
                        assertThat(exception.getCause()).isNull();
                        assertThat(exception.getMessage()).doesNotContain("user-secret-text");
                    });
            assertThatThrownBy(() -> AuthorAnswer.parse(null))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(RunError.LLM_BAD_ANSWER));
        }
    }

    @Nested
    @DisplayName("the author prompt")
    class Prompt {

        @Test
        @DisplayName("startup fails when the author answer of maxTopics topics and maxGroups groups can pass the "
                + "author task's max_tokens")
        void authorAnswerBudget() {
            var config = TopicsTestAlgorithm.config();
            config.setMaxGroups(200);

            assertThatThrownBy(() -> TopicsTestAlgorithm.algorithm(config)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("author answer");
        }

        @Test
        @DisplayName("examples: the repeated texts, then the ones nearest the centre, no text twice")
        void examples() {
            String[] texts = {"a", "a", "a", "b", "c", "d", "e"};
            float[] vectors = {1, 0, 1, 0, 1, 0, 0.99f, 0.141f, 0.95f, 0.312f, 0.8f, 0.6f, 0.6f, 0.8f};
            int[] labels = {0, 0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0, 0, 1};

            var examples = authorExamples(texts, vectors, labels, centres, 2, 2);

            assertThat(examples).hasSize(2);
            assertThat(examples.get(0)).containsExactly("a", "b", "c", "d");
            assertThat(examples.get(1)).isEmpty();
            assertThat(authorExamples(texts, vectors, labels, centres, 5, 0).get(0))
                    .containsExactly("a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("examples, no text repeats: all are the nearest ones, ties by trace order, not code point order")
        void examplesWithoutRepeats() {
            String[] texts = {"b", "a", "d", "c", "e"};
            float[] vectors = {1, 0, 1, 0, 0.8f, 0.6f, 0.6f, 0.8f, 0, 1};
            int[] labels = {0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(authorExamples(texts, vectors, labels, centres, 2, 1).get(0))
                    .containsExactly("b", "a", "d");
        }

        @Test
        @DisplayName("examples: a text is as near as its first trace, the vector k-means grouped")
        void examplesNearnessOfFirstTrace() {
            String[] texts = {"a", "b", "a"};
            float[] vectors = {0.6f, 0.8f, 0.8f, 0.6f, 1, 0};
            int[] labels = {0, 0, 0};
            float[] centres = {1, 0};

            assertThat(authorExamples(texts, vectors, labels, centres, 0, 2).get(0)).containsExactly("b", "a");
        }

        @Test
        @DisplayName("examples, some texts repeat: the repeated ones first, the free slots go to the nearest ones")
        void examplesWithSomeRepeats() {
            String[] texts = {"z", "a", "z", "d", "c", "b"};
            float[] vectors = {0, 1, 0.6f, 0.8f, 0, 1, 1, 0, 0.95f, 0.312f, 0.8f, 0.6f};
            int[] labels = {0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(authorExamples(texts, vectors, labels, centres, 3, 1).get(0))
                    .containsExactly("z", "d", "c", "b");
        }

        @Test
        @DisplayName("examples, more repeated texts than slots: most frequent first, ties by code point order")
        void examplesWithMoreRepeatsThanSlots() {
            String[] texts = {"y", "x", "y", "w", "x", "y", "w", "v"};
            float[] vectors = {0, 1, 0.8f, 0.6f, 0, 1, 0.6f, 0.8f, 0.8f, 0.6f, 0, 1, 0.6f, 0.8f, 1, 0};
            int[] labels = {0, 0, 0, 0, 0, 0, 0, 0};
            float[] centres = {1, 0};

            assertThat(authorExamples(texts, vectors, labels, centres, 2, 1).get(0))
                    .containsExactly("y", "w", "v");
            assertThat(authorExamples(texts, vectors, labels, centres, 2, 2).get(0))
                    .containsExactly("y", "w", "v", "x");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedInputs")
        @DisplayName("examples: malformed input is rejected with an IllegalArgumentException")
        void examplesRejectMalformedInput(String label, ThrowingCallable call) {
            assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
        }

        static Stream<Arguments> malformedInputs() {
            float[] vectors = {1, 0, 0, 1};
            var merged = IntentMerger.merge(new String[]{"a", "b"}, vectors, 2);
            return Stream.of(
                    Arguments.of("centres that are not whole rows",
                            (ThrowingCallable) () -> GroupSummaries.authorExamples(merged, new int[]{0, 0},
                                    new float[]{1, 0, 0}, 2, 2)),
                    Arguments.of("a label outside the groups",
                            (ThrowingCallable) () -> GroupSummaries.authorExamples(merged, new int[]{0, 1},
                                    new float[]{1, 0}, 2, 2)),
                    Arguments.of("a negative example count",
                            (ThrowingCallable) () -> GroupSummaries.authorExamples(merged, new int[]{0, 0},
                                    new float[]{1, 0}, -1, 2)),
                    Arguments.of("a null text",
                            (ThrowingCallable) () -> IntentMerger.merge(new String[]{"a", null}, vectors, 2)));
        }

        @Test
        @DisplayName("the prompt lists the groups largest first with a whole-percent share and the given examples")
        void prompt() {
            var groups = List.of(
                    GroupSummary.builder().group(0).size(10).share(0.1).build(),
                    GroupSummary.builder().group(1).size(40).share(0.4).build(),
                    GroupSummary.builder().group(2).size(0).build());

            var prompt = TopicPrompts.author(new TopicTasks().author(), groups, 10,
                    List.of(List.of("zero"), List.of("one", "uno"), List.of()));

            assertThat(prompt)
                    .contains("at most 10 topics")
                    .contains(
                            "  cluster 1: 40% of traffic\n    - one\n    - uno\n  cluster 0: 10% of traffic\n    - zero")
                    .contains("\"assignment\"")
                    .doesNotContain("cluster 2:", TopicTasks.CLUSTERS_PLACEHOLDER, TopicTasks.MAX_TOPICS_PLACEHOLDER);
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        private final List<GroupSummary> groups = IntStream.range(0, 14)
                .mapToObj(j -> GroupSummary.builder().group(j).size(100 - j).build())
                .toList();

        @Test
        @DisplayName("11 topics keep the 10 largest; catch-alls are dropped before the cut")
        void cap() {
            var topics = new ArrayList<AuthorTopic>();
            topics.add(new AuthorTopic("Other Stuff", "d", List.of(0)));
            for (int j = 1; j <= 11; j++) {
                topics.add(new AuthorTopic("Topic " + j, "d", List.of(j)));
            }
            var applied = AuthorApplier.apply(authorAnswer(topics), groups, 10);
            assertThat(applied.topics()).extracting(AppliedTopic::name)
                    .containsExactly("Topic 1", "Topic 2", "Topic 3", "Topic 4", "Topic 5", "Topic 6", "Topic 7",
                            "Topic 8", "Topic 9", "Topic 10");
            assertThat(applied.other()).containsExactly(0, 11, 12, 13);
        }

        @Test
        @DisplayName("a group goes to the largest topic that lists it; invalid and repeated ids are ignored")
        void claims() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic(" Small ", " d ", List.of(5, 99, -1)),
                    new AuthorTopic("Big", "d", List.of(1, 2, 2, 5)),
                    new AuthorTopic("Empty", "d", List.of(1))));
            var applied = AuthorApplier.apply(answer, groups, 10);
            assertThat(applied.topics()).hasSize(1);
            assertThat(applied.topics().getFirst().name()).isEqualTo("Big");
            assertThat(applied.topics().getFirst().groups()).containsExactly(1, 2, 5);
            assertThat(applied.other()).containsExactly(0, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13);
        }

        @Test
        @DisplayName("names and definitions are stripped as Python strips them; ties keep the answer's order")
        void strip() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic(" B ", " b ", List.of(3)),
                    new AuthorTopic("A", "a", List.of(4, -5))));
            var flat = IntStream.range(0, 6)
                    .mapToObj(j -> GroupSummary.builder().group(j).size(10).build()).toList();
            var applied = AuthorApplier.apply(answer, flat, 10);
            assertThat(applied.topics()).extracting(AppliedTopic::name).containsExactly("B", "A");
            assertThat(applied.topics().getFirst().definition()).isEqualTo("b");
        }

        @Test
        @DisplayName("topics with the same name merge into the largest one, at the first one's place")
        void sameNames() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic("Email Drafting", "small", List.of(9, 4)),
                    new AuthorTopic("Billing", "d", List.of(2)),
                    new AuthorTopic(" email\u00A0 drafting", "large", List.of(3, 4)),
                    new AuthorTopic("Email Drafts", "d", List.of(10))));
            var applied = AuthorApplier.apply(answer, groups, 10);
            assertThat(applied.topics()).extracting(AppliedTopic::name)
                    .containsExactly("email\u00A0 drafting", "Billing", "Email Drafts");
            var email = applied.topics().getFirst();
            assertThat(email.definition()).isEqualTo("large");
            assertThat(email.groups()).containsExactly(3, 4, 9);
        }

        @Test
        @DisplayName("a blank name is no name: its groups go to Other and it takes no slot")
        void blankName() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic(" \u00A0 ", "d", List.of(1)),
                    new AuthorTopic("Billing", "d", List.of(2))));
            var applied = AuthorApplier.apply(answer, groups, 1);
            assertThat(applied.topics()).extracting(AppliedTopic::name).containsExactly("Billing");
            assertThat(applied.other()).contains(1).doesNotContain(2);
        }

        @Test
        @DisplayName("a name over 255 code points is cut to 255, so the save can't fail on it")
        void longName() {
            var name = "\uD83D\uDE00".repeat(300);
            var answer = authorAnswer(List.of(new AuthorTopic(name, "d", List.of(1))));
            var stored = AuthorApplier.apply(answer, groups, 10).topics().getFirst().name();
            assertThat(stored.codePointCount(0, stored.length())).isEqualTo(TopicText.MAX_NAME_LENGTH);
            assertThat(TopicText.name(" Billing ")).isEqualTo("Billing");
        }

        @Test
        @DisplayName("two names that share their first 255 code points are one stored name, so they merge")
        void sameNameAfterTheCut() {
            var prefix = "a".repeat(TopicText.MAX_NAME_LENGTH);
            var answer = authorAnswer(List.of(
                    new AuthorTopic(prefix + " one", "d", List.of(1)),
                    new AuthorTopic(prefix + " two", "d", List.of(2))));

            var applied = AuthorApplier.apply(answer, groups, 10);

            assertThat(applied.topics()).singleElement().satisfies(topic -> {
                assertThat(topic.name()).isEqualTo(prefix);
                assertThat(topic.groups()).containsExactly(1, 2);
            });
        }

        @Test
        @DisplayName("same name: outer and inner whitespace as Python splits it, and case, don't matter")
        void sameNameKey() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic("\u00A0 Email \t\u2003Drafting \n", "d", List.of(1)),
                    new AuthorTopic("email drafting", "d", List.of(2))));

            var applied = AuthorApplier.apply(answer, groups, 10);

            assertThat(applied.topics()).singleElement().satisfies(topic -> assertThat(topic.groups())
                    .containsExactly(1, 2));
        }

        @Test
        @DisplayName("a topic that claims no group takes no slot")
        void emptyTopicTakesNoSlot() {
            var answer = authorAnswer(List.of(
                    new AuthorTopic("Nothing", "d", List.of(99)),
                    new AuthorTopic("Billing", "d", List.of(2))));

            var applied = AuthorApplier.apply(answer, groups, 1);

            assertThat(applied.topics()).extracting(AppliedTopic::name).containsExactly("Billing");
        }
    }

    @Nested
    @DisplayName("assignment")
    class Assignment {

        @Test
        @DisplayName("ties go to the lower prototype; below 0.75 is Other")
        void ties() {
            var a = new float[]{1, 0};
            var assigner = assigner(List.of(a, a.clone()), List.of(0, 1), List.of());
            assertThat(assigner.assign(new float[]{1, 0, 0.7f, 0.71414284f})).containsExactly(0,
                    TopicAlgorithm.OTHER);
        }

        @Test
        @DisplayName("the winner: ties to the earlier prototype, a gated known-Other one never wins, below 0.75 none")
        void winners() {
            var a = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var assigner = new PrototypeAssigner(List.of(a, a.clone()), List.of(0, 1), List.of(close, far), 2, 0.75,
                    OTHER_GATE);

            assertThat(assigner.winners(new float[]{1, 0, 0.96f, 0.28f, 0, 1, 0.6f, -0.8f}))
                    .containsExactly(0, 0, 2, PrototypeAssigner.NO_WINNER);
        }

        @ParameterizedTest(name = "{0} dimensions, {1} prototypes")
        @CsvSource({"384, 161", "384, 160", "13, 7", "8, 2", "5, 1"})
        @DisplayName("two prototypes per pass: every cosine is the single dot's bit for bit, and the winners are the "
                + "same as one prototype at a time")
        void twoPerPass(int dimensions, int count) {
            var random = new SplittableRandom(dimensions * 1_000L + count);
            int n = 500;
            var vectors = randomRows(random, n, dimensions);
            var flat = randomRows(random, count, dimensions);
            var prototypes = new ArrayList<float[]>();
            for (int p = 0; p < count; p++) {
                // Every fifth prototype repeats the one before it, so ties come up.
                int from = p > 0 && p % 5 == 0 ? p - 1 : p;
                prototypes.add(Arrays.copyOfRange(flat, from * dimensions, (from + 1) * dimensions));
            }
            var cosines = new double[2];
            for (int i = 0; i < n; i++) {
                for (int p = 0; p + 1 < count; p++) {
                    VectorMath.dot2(vectors, i * dimensions, flat, p * dimensions, (p + 1) * dimensions, dimensions,
                            cosines);
                    assertThat(Double.doubleToRawLongBits(cosines[0])).isEqualTo(Double.doubleToRawLongBits(
                            VectorMath.dot(vectors, i * dimensions, flat, p * dimensions, dimensions)));
                    assertThat(Double.doubleToRawLongBits(cosines[1])).isEqualTo(Double.doubleToRawLongBits(
                            VectorMath.dot(vectors, i * dimensions, flat, (p + 1) * dimensions, dimensions)));
                }
            }
            var owners = IntStream.range(0, count).boxed().toList();
            var assigner = new PrototypeAssigner(prototypes, owners, List.of(), dimensions,
                    Double.NEGATIVE_INFINITY, OTHER_GATE);

            var expected = new int[n];
            for (int i = 0; i < n; i++) {
                int best = PrototypeAssigner.NO_WINNER;
                double bestCosine = Double.NEGATIVE_INFINITY;
                for (int p = 0; p < count; p++) {
                    double cosine = VectorMath.dot(vectors, i * dimensions, prototypes.get(p), 0, dimensions);
                    if (cosine > bestCosine) {
                        bestCosine = cosine;
                        best = p;
                    }
                }
                expected[i] = best;
            }
            assertThat(assigner.winners(vectors)).containsExactly(expected);
        }

        private static float[] randomRows(SplittableRandom random, int rows, int dimensions) {
            var out = new float[rows * dimensions];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (random.nextDouble() * 2 - 1);
            }
            return out;
        }

        @Test
        @DisplayName("a known-Other prototype at 0.95 or more to a topic prototype is left out")
        void gate() {
            var topic = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var assigner = assigner(List.of(topic), List.of(0), List.of(close, far));
            assertThat(assigner.gatedOther()).isEqualTo(1);
            assertThat(assigner.keptOther()).containsExactly(far);
            assertThat(assigner.assign(new float[]{0.96f, 0.28f, 0, 1})).containsExactly(0, TopicAlgorithm.OTHER);
        }

        @Test
        @DisplayName("the kept Other prototypes alone assign as all of them do, and none is within 0.95 of a topic")
        void keptOther() {
            var topic = new float[]{1, 0};
            var close = new float[]{0.96f, 0.28f};
            var far = new float[]{0, 1};
            var all = assigner(List.of(topic), List.of(0), List.of(close, far));

            assertThat(all.keptOther()).containsExactly(far);
            for (var other : all.keptOther()) {
                assertThat((double) other[0] * topic[0] + (double) other[1] * topic[1])
                        .isLessThan(OTHER_GATE);
            }
            var kept = assigner(List.of(topic), List.of(0), all.keptOther());
            assertThat(kept.gatedOther()).isZero();
            var vectors = new float[2 * 360];
            for (int d = 0; d < 360; d++) {
                vectors[2 * d] = (float) Math.cos(Math.toRadians(d));
                vectors[2 * d + 1] = (float) Math.sin(Math.toRadians(d));
            }
            assertThat(kept.assign(vectors)).containsExactly(all.assign(vectors));
        }

        @Test
        @DisplayName("the thresholds come from the config: a higher minimum cosine sends a near trace to Other")
        void thresholds() {
            var topic = new float[]{1, 0};
            var near = new float[]{0.8f, 0.6f};
            assertThat(new PrototypeAssigner(List.of(topic), List.of(0), List.of(), 2, 0.75, OTHER_GATE).assign(near))
                    .containsExactly(0);
            assertThat(new PrototypeAssigner(List.of(topic), List.of(0), List.of(), 2, 0.9, OTHER_GATE).assign(near))
                    .containsExactly(TopicAlgorithm.OTHER);
            var other = new float[]{0.8f, 0.6f};
            assertThat(new PrototypeAssigner(List.of(topic), List.of(0), List.of(other), 2, 0.75, 0.7).gatedOther())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the 8-sum dot labels as a plain sum does on 384 dimensions")
        void dotOverFullVectors() {
            var random = new SplittableRandom(7);
            var prototypes = new ArrayList<float[]>();
            var owners = new ArrayList<Integer>();
            for (int p = 0; p < 20; p++) {
                prototypes.add(unit(random, TopicEmbedding.DIMENSIONS));
                owners.add(p);
            }
            int n = 500;
            var vectors = new float[n * TopicEmbedding.DIMENSIONS];
            for (int i = 0; i < n; i++) {
                var base = prototypes.get(i % 20);
                var noise = unit(random, TopicEmbedding.DIMENSIONS);
                var mixed = new float[TopicEmbedding.DIMENSIONS];
                for (int t = 0; t < mixed.length; t++) {
                    mixed[t] = base[t] + 0.6f * noise[t];
                }
                System.arraycopy(normalized(mixed), 0, vectors, i * mixed.length, mixed.length);
            }

            var labels = new PrototypeAssigner(prototypes, owners, List.of(), TopicEmbedding.DIMENSIONS, 0.75,
                    OTHER_GATE).assign(vectors);

            for (int i = 0; i < n; i++) {
                int best = -1;
                double bestCosine = Double.NEGATIVE_INFINITY;
                for (int p = 0; p < prototypes.size(); p++) {
                    double cosine = 0;
                    for (int t = 0; t < TopicEmbedding.DIMENSIONS; t++) {
                        cosine += (double) vectors[i * TopicEmbedding.DIMENSIONS + t] * prototypes.get(p)[t];
                    }
                    if (cosine > bestCosine) {
                        bestCosine = cosine;
                        best = p;
                    }
                }
                assertThat(labels[i]).as("trace %s", i).isEqualTo(bestCosine < 0.75 ? TopicAlgorithm.OTHER : best);
            }
        }

        private static float[] unit(SplittableRandom random, int dimensions) {
            var vector = new float[dimensions];
            for (int t = 0; t < dimensions; t++) {
                vector[t] = (float) random.nextGaussian();
            }
            return normalized(vector);
        }

        private static float[] normalized(float[] vector) {
            double norm = 0;
            for (float value : vector) {
                norm += (double) value * value;
            }
            var out = new float[vector.length];
            for (int t = 0; t < vector.length; t++) {
                out[t] = (float) (vector[t] / Math.sqrt(norm));
            }
            return out;
        }

        private static PrototypeAssigner assigner(List<float[]> topics, List<Integer> owners, List<float[]> others) {
            return new PrototypeAssigner(topics, owners, others, 2, 0.75, OTHER_GATE);
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
            assertThatThrownBy(() -> KMeans.fit(points, new double[]{1, weight}, dimensions, 1, 0, MAX_ITERATIONS,
                    RESTARTS))
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

        @ParameterizedTest
        @CsvSource({"0, 3, maxIterations must be positive", MAX_ITERATIONS + ", 0, restarts must be positive"})
        @DisplayName("rejects a non-positive iteration cap and restart count")
        void invalidLimits(int maxIterations, int restarts, String message) {
            assertThatThrownBy(() -> KMeans.fit(new float[]{1, 0, 0, 1}, new double[]{1, 1}, 2, 1, 0, maxIterations,
                    restarts))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(message);
        }

        @Test
        @DisplayName("k equal to the number of points: duplicate points share a group, every other point has its own")
        void kEqualsN() {
            var points = new float[]{1, 0, 0, 1, 1, 0, 0.6f, 0.8f};
            var result = KMeans.fit(points, new double[]{1, 1, 1, 1}, 2, 4, 0, MAX_ITERATIONS, RESTARTS);
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
                assertThatThrownBy(
                        () -> KMeans.fit(new float[]{1, 0, 0, 1}, new double[]{1, 1}, 2, 2, 0, MAX_ITERATIONS,
                                RESTARTS))
                        .isInstanceOf(java.util.concurrent.CancellationException.class);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("k-means with Elkan's bounds gives plain Lloyd's labels")
    class KMeansSameLabels {

        @ParameterizedTest(name = "n {0}, {1} dims, {2} clusters, noise {3}, k {4}, seed {5}, cap {6}, capped {7}")
        @CsvSource({
                "1500, 384, 40, 0.15, 50, 0, " + MAX_ITERATIONS + ", false",
                "1500, 384, 40, 0.15, 50, 1, " + MAX_ITERATIONS + ", false",
                "2000, 13, 10, 0.5, 30, 0, " + MAX_ITERATIONS + ", false",
                // 20 distinct points, each repeated: k above that leaves clusters empty, reseeded on a duplicate.
                "300, 16, 20, 0, 30, 0, " + MAX_ITERATIONS + ", false",
                // Random directions: the fit stops at the cap.
                "300, 8, 300, 10, 20, 0, 3, true"})
        @DisplayName("same labels, iterations and centres as the plain fit on a fixed synthetic set")
        void samePlainFit(int n, int dimensions, int clusters, double noise, int k, long seed, int maxIterations,
                boolean capped) {
            var points = syntheticPoints(n, dimensions, clusters, noise);
            var random = new SplittableRandom(13);
            var weights = new double[n];
            for (int i = 0; i < n; i++) {
                weights[i] = 1 + random.nextInt(4);
            }

            var plain = PlainKMeans.fit(points, weights, dimensions, k, seed, maxIterations, RESTARTS);
            var fast = KMeans.fit(points, weights, dimensions, k, seed, maxIterations, RESTARTS);

            // The capped row covers the stop at the cap, the others a converged fit.
            assertThat(plain.iterations() == maxIterations).as("stops at the cap").isEqualTo(capped);
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

    /** {@link GroupSummaries#authorExamples} on 2-dimensional traces, merged as the discovery run merges them. */
    private static List<List<String>> authorExamples(String[] texts, float[] vectors, int[] traceLabels,
            float[] centres, int frequent, int extra) {
        var merged = IntentMerger.merge(texts, vectors, 2);
        var labels = new int[merged.size()];
        for (int u = 0; u < labels.length; u++) {
            labels[u] = traceLabels[merged.firstIndex()[u]];
        }
        return GroupSummaries.authorExamples(merged, labels, centres, frequent, extra);
    }

    private static AuthorAnswer authorAnswer(List<AuthorTopic> topics) {
        return AuthorAnswer.builder().topics(topics).build();
    }
}
