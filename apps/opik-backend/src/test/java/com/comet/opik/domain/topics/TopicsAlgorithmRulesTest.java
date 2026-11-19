package com.comet.opik.domain.topics;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

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

    private static final int MAX_ITERATIONS = 150;
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
        @CsvSource({"0, 3, maxIterations must be positive", "150, 0, restarts must be positive"})
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
                "1500, 384, 40, 0.15, 50, 0, 150, false",
                "1500, 384, 40, 0.15, 50, 1, 150, false",
                "2000, 13, 10, 0.5, 30, 0, 150, false",
                // 20 distinct points, each repeated: k above that leaves clusters empty, reseeded on a duplicate.
                "300, 16, 20, 0, 30, 0, 150, false",
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

    /** {@link GroupSummaries#authorExamples} on 2-dimensional traces, merged as the first build merges them. */
    private static List<List<String>> authorExamples(String[] texts, float[] vectors, int[] traceLabels,
            float[] centres, int frequent, int extra) {
        var merged = IntentMerger.merge(texts, vectors, 2);
        var labels = new int[merged.size()];
        for (int u = 0; u < labels.length; u++) {
            labels[u] = traceLabels[merged.firstIndex()[u]];
        }
        return GroupSummaries.authorExamples(merged, labels, centres, frequent, extra);
    }
}
