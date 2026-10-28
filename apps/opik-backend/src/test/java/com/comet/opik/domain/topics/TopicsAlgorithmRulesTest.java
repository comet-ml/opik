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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        @DisplayName("shares format as the stand's 100 * size / n")
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

        @ParameterizedTest(name = "{0} is a catch-all: {1}")
        @CsvSource({
                "Other Requests, true",
                "Miscellaneous, true",
                "General Questions, true",
                "Various Tasks, true",
                "OTHERS, true",
                "Generalization Help, false",
                "Brother Projects, false",
                "Daily Briefing Reports, false"})
        @DisplayName("catch-all names")
        void catchAll(String name, boolean expected) {
            assertThat(AuthorApplier.isCatchAll(name)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("the author answer")
    class Answer {

        @Test
        @DisplayName("a JSON object inside prose parses, from the first brace to the last")
        void prose() {
            var answer = AuthorAnswer.parse(TextNode.valueOf(
                    "Sure:\n{\"topics\": [{\"name\": \"A\", \"definition\": \"d\", \"clusters\": [0]}], \"other\": [1]}\nDone"));
            assertThat(answer.topics()).singleElement()
                    .satisfies(topic -> assertThat(topic.clusters()).containsExactly(0));
            assertThat(answer.other()).containsExactly(1);
        }

        @Test
        @DisplayName("an object parses as is, unknown keys ignored")
        void object() throws IOException {
            var answer = AuthorAnswer.parse(InferenceJson.MAPPER.readTree(
                    "{\"topics\": [], \"other\": [], \"note\": 1}"));
            assertThat(answer.topics()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "not json at all",
                "{\"topics\": []}",
                "{\"other\": []}",
                "{\"topics\": [{\"name\": \"A\", \"clusters\": [0]}], \"other\": []}",
                "{\"topics\": [{\"name\": \"A\", \"definition\": \"d\", \"clusters\": [1.5]}], \"other\": []}",
                "{\"topics\": [{\"name\": 3, \"definition\": \"d\", \"clusters\": [1]}], \"other\": []}",
                "[1, 2]"})
        @DisplayName("an answer off the schema is qwen_bad_answer")
        void bad(String text) {
            assertThatThrownBy(() -> AuthorAnswer.parse(TextNode.valueOf(text)))
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(Code.QWEN_BAD_ANSWER));
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
            var applied = AuthorApplier.apply(new AuthorAnswer(topics, List.of()), groups, 10);
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
                    new AuthorAnswer.Topic("Empty", "d", List.of(1))), List.of(3));
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
                    new AuthorAnswer.Topic("A", "a", List.of(4, -5))), List.of());
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
                    new AuthorAnswer.Topic("Email Drafts", "d", List.of(10))), List.of());
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
                    new AuthorAnswer.Topic("Billing", "d", List.of(2))), List.of());
            var applied = AuthorApplier.apply(answer, groups, 1);
            assertThat(applied.topics()).extracting(AuthorApplier.NewTopic::name).containsExactly("Billing");
            assertThat(applied.other()).contains(1).doesNotContain(2);
        }

        @Test
        @DisplayName("a name over 255 code points is cut to 255, so the save can't fail on it")
        void longName() {
            var name = "\uD83D\uDE00".repeat(300);
            var answer = new AuthorAnswer(List.of(new AuthorAnswer.Topic(name, "d", List.of(1))), List.of());
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
}
