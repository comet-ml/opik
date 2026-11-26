package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("the rename call names each merged topic; a name left out or taken keeps the first former name")
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
                            {"id": 1, "name": "travel tips", "definition": "Taken."}]}
                """);
        var names = TopicsTestAlgorithm.finished(ask.next().apply(answer));

        assertThat(names).extracting(TopicMerger.Name::name).containsExactly("Weather Forecasts", "Flights", "Recipes");
    }
}
