package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("topics: merge of topics that cover the same traces")
class TopicMergerTest {

    @Test
    @DisplayName("folds the smaller of two topics with near centres into the larger, leaves a distinct one alone")
    void foldsNearPairOnly() {
        float c = (float) Math.cos(0.1);
        float s = (float) Math.sin(0.1);
        // topic 0: three traces at angle 0.1 and -0.1 around east; topic 1: two traces at east; topic 2: north
        float[] vectors = {c, s, c, -s, c, s, 1, 0, 1, 0, 0, 1};
        int[] labels = {0, 0, 0, 1, 1, 2};

        var merges = TopicMerger.merges(vectors, labels, 3, new int[]{0, 1, 2}, 2, 0.96);

        assertThat(merges).extracting(TopicMerger.Merge::kept, TopicMerger.Merge::absorbed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 1));
        assertThat(TopicMerger.merges(vectors, labels, 3, new int[]{0, 1, 2}, 2, 2.0)).isEmpty();
    }

    @Test
    @DisplayName("the rename call names each merged topic; a name left out, taken or another's fallback keeps the first former name")
    void renames() {
        var merged = List.of(
                new TopicMerger.Merged(List.of("Weather by City", "Weather by Country"),
                        List.of("by city", "by country"), 0.6, List.of("rain in Paris tomorrow?")),
                new TopicMerger.Merged(List.of("Flights", "Trips"), List.of("flights", "trips"), 0.1,
                        List.of("cheap flights?")),
                new TopicMerger.Merged(List.of("Recipes", "Cooking"), List.of("recipes", "cooking"), 0.1,
                        List.of("pasta recipe")));
        var step = TopicMerger.rename(merged, Set.of("Travel Tips"), new TopicTasks());

        var ask = (NightStep.Ask<List<TopicMerger.Name>>) step;
        assertThat(ask.request().prompt()).contains("Weather by Country", "rain in Paris tomorrow?");
        var answer = JsonUtils.getJsonNodeFromString("""
                {"topics": [{"id": 0, "name": "Weather Forecasts", "definition": "Forecasts."},
                            {"id": 1, "name": "travel tips", "definition": "Taken."},
                            {"id": 2, "name": "flights", "definition": "The fallback of id 1."}]}
                """);
        var names = TopicsTestAlgorithm.finished(ask.next().apply(answer));

        assertThat(names).extracting(TopicMerger.Name::name).containsExactly("Weather Forecasts", "Flights", "Recipes");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    @DisplayName("the member sums taken per group before the author call give the merges the window's vectors give, "
            + "with a gated Other centre and a tie between two equal centres")
    void groupSumsMergeAsTheVectors(long seed) {
        int d = TopicEmbedding.DIMENSIONS;
        int groups = 30;
        var random = new SplittableRandom(seed);
        var centres = new float[groups * d];
        for (int c = 0; c < groups; c++) {
            for (int t = 0; t < d; t++) {
                centres[c * d + t] = (float) random.nextGaussian();
            }
        }
        // Near twins (cosine about 0.99, over otherGate): 0 and 1, 2 and 3, 6 and 7. 4 and 5 are equal.
        for (int[] twin : new int[][]{{0, 1}, {2, 3}, {6, 7}}) {
            for (int t = 0; t < d; t++) {
                centres[twin[1] * d + t] = centres[twin[0] * d + t] + (float) (random.nextGaussian() * 0.1);
            }
        }
        System.arraycopy(centres, 4 * d, centres, 5 * d, d);
        centres = unitRows(centres, d);
        int n = 6_000;
        var vectors = new float[n * d];
        for (int i = 0; i < n; i++) {
            int near = random.nextInt(groups);
            for (int t = 0; t < d; t++) {
                vectors[i * d + t] = centres[near * d + t] + (float) (random.nextGaussian() * 0.04);
            }
        }
        vectors = unitRows(vectors, d);
        // Topics: 0 {0}, 1 {2}, 2 {3}, 3 {5}, 4 {6}, 5 {7}, 6 {8, 9}; then two groups per topic and one Other. Other: 1, 4
        // and the rest. 1 and 4 are gated (near 0 and equal to 5), 2/3 and 6/7 are topics that cover the same traces.
        var topicGroups = new ArrayList<List<Integer>>(List.of(List.of(0), List.of(2), List.of(3), List.of(5),
                List.of(6), List.of(7), List.of(8, 9)));
        var otherGroups = new ArrayList<Integer>(List.of(1, 4));
        int g = 10;
        for (; g + 2 < groups; g += 3) {
            topicGroups.add(List.of(g, g + 1));
            otherGroups.add(g + 2);
        }
        // Every group is a topic's or Other, as AuthorApplier leaves it.
        for (; g < groups; g++) {
            otherGroups.add(g);
        }
        float[] rows = centres;
        var topicPrototypes = topicGroups.stream().flatMap(List::stream)
                .map(group -> Arrays.copyOfRange(rows, group * d, (group + 1) * d)).toList();
        var owners = IntStream.range(0, topicGroups.size())
                .flatMap(t -> IntStream.range(0, topicGroups.get(t).size()).map(__ -> t)).boxed().toList();
        var assigner = new PrototypeAssigner(topicPrototypes, owners,
                otherGroups.stream().map(group -> Arrays.copyOfRange(rows, group * d, (group + 1) * d)).toList(), d,
                0.75, 0.95);
        int topicCount = topicGroups.size();
        var candidates = IntStream.range(0, topicCount).toArray();
        var table = PrototypeAssigner.cosines(vectors, centres, groups, d);
        var sums = GroupVectorSums.of(vectors, table, centres, groups, d, 0.75, 0.95);
        var columnOf = assigner.columnsOf(groups,
                topicGroups.stream().flatMap(List::stream).mapToInt(Integer::intValue).toArray(),
                otherGroups.stream().mapToInt(Integer::intValue).toArray());

        assertThat(assigner.gatedOther()).isEqualTo(2);
        assertThat(sums.rankedTraces()).isPositive();
        var expected = TopicMerger.members(vectors, assigner.assign(vectors), topicCount, candidates, d);
        var actual = sums.members(topicCount, candidates, columnOf, assigner::owner);
        assertThat(actual.counts()).containsExactly(expected.counts());
        for (int t = 0; t < topicCount; t++) {
            assertThat(actual.sums()[t]).containsExactly(expected.sums()[t], within(1e-9));
        }
        for (double mergeCosine : new double[]{0.96, 0.9, 0.5, -1}) {
            var byVectors = TopicMerger.merges(vectors, assigner.assign(vectors), topicCount, candidates, d,
                    mergeCosine);
            var bySums = TopicMerger.merges(sums.members(topicCount, candidates, columnOf, assigner::owner),
                    candidates, mergeCosine);
            assertThat(bySums).extracting(TopicMerger.Merge::kept, TopicMerger.Merge::absorbed)
                    .containsExactlyElementsOf(byVectors.stream()
                            .map(merge -> org.assertj.core.groups.Tuple.tuple(merge.kept(), merge.absorbed()))
                            .toList());
            for (int m = 0; m < byVectors.size(); m++) {
                assertThat(bySums.get(m).cosine()).isCloseTo(byVectors.get(m).cosine(), within(1e-12));
            }
            if (mergeCosine == 0.96) {
                assertThat(byVectors).extracting(TopicMerger.Merge::absorbed).isNotEmpty();
            }
        }
    }

    private static float[] unitRows(float[] rows, int dimensions) {
        var out = rows.clone();
        for (int r = 0; r < out.length / dimensions; r++) {
            double norm = 0;
            for (int t = 0; t < dimensions; t++) {
                norm += (double) out[r * dimensions + t] * out[r * dimensions + t];
            }
            for (int t = 0; t < dimensions; t++) {
                out[r * dimensions + t] = (float) (out[r * dimensions + t] / Math.sqrt(norm));
            }
        }
        return out;
    }
}
