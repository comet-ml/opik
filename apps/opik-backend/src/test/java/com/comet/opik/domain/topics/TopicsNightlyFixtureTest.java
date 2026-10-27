package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 6.1 of the stage 2b plan: each nightly update of the fixture, started from the stand's saved state of the night
 * before, with the stand's k-means results and Qwen answers injected, equals the stand byte for byte: every prompt, the
 * decisions, the topic list and its counters, the prototypes and the final labels.
 */
@DisplayName("topics nightly update: the algorithm against the stand's three-night fixture")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicsNightlyFixtureTest {

    private static final int D = TopicsNightlyFixture.DIMENSIONS;
    private static final int MAX_TOPICS = 10;

    private TopicsNightlyFixture fixture;

    @BeforeAll
    void beforeAll() {
        fixture = TopicsNightlyFixture.load();
    }

    @ParameterizedTest(name = "night {0}")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("a night equals the stand: prompts, decisions, topics, counters, prototypes, labels")
    void night(int q) {
        var first = fixture.night(0);
        var before = fixture.night(q - 1);
        var night = fixture.night(q);
        var state = before.stateFor(night, first.date());
        var calls = new ArrayList<String>();

        var result = NightlyUpdate.run(state, night.nightWindow(), MAX_TOPICS, request -> cluster(night, request),
                (tag, prompt, schema, maxTokens) -> {
                    calls.add(tag);
                    assertThat(prompt).as("%s prompt", tag).isEqualTo(night.prompt(tag));
                    var recorded = night.answer(tag);
                    assertThat(maxTokens).as("%s max tokens", tag).isEqualTo(recorded.get("max_tokens").asInt());
                    return copy(recorded.get("output"));
                });

        var expected = night.expected;
        assertThat(calls).containsExactlyElementsOf(expected.get("qwen_calls").findValuesAsText("name"));
        assertThat(result.reviewNight()).isEqualTo(expected.get("review_night").asBoolean());
        assertThat(result.review()).isEqualTo(expected.get("review").asBoolean());
        assertThat(result.unassigned()).isEqualTo(expected.get("unassigned").asInt());
        assertThat(result.candidates()).containsExactlyElementsOf(ints(expected.get("candidates")));
        assertThat(result.big()).containsExactlyElementsOf(ints(expected.get("big")));
        if (expected.has("after_revise")) {
            assertThat(ids(state, result.afterRevise())).isEqualTo(longMap(expected.get("after_revise")));
            var pairs = new ArrayList<List<Long>>();
            result.verified().pairs().forEach(pair -> pairs.add(List.of((long) pair[0],
                    TopicsNightlyFixture.counter(state.topic(pair[1]).getId()))));
            var expectedPairs = new ArrayList<List<Long>>();
            expected.get("verify_pairs").forEach(pair -> expectedPairs.add(List.of(pair.get(0).asLong(),
                    pair.get(1).asLong())));
            assertThat(pairs).isEqualTo(expectedPairs);
            var verdicts = new TreeMap<Integer, Boolean>();
            expected.get("verdicts").fields()
                    .forEachRemaining(entry -> verdicts.put(Integer.parseInt(entry.getKey()),
                            entry.getValue().asBoolean()));
            assertThat(new TreeMap<>(result.verified().verdicts())).isEqualTo(verdicts);
            var forced = new ArrayList<String>();
            result.forced().forEach(outcome -> forced.add(outcome.group() + ":" + switch (outcome.result()) {
                case BORN -> "born:" + TopicsNightlyFixture.counter(state.topic(outcome.topic()).getId());
                case OTHER_LOOSE -> "other_loose";
                case OTHER_FULL -> "other_full";
                case OTHER_NAME -> "other_name";
            }));
            var expectedForced = new ArrayList<String>();
            expected.get("forced").forEach(outcome -> expectedForced.add(outcome.get("cluster").asInt() + ":"
                    + outcome.get("result").asText()
                    + (outcome.has("topic") ? ":" + outcome.get("topic").asLong() : "")));
            assertThat(forced).isEqualTo(expectedForced);
        }
        assertThat(ids(state, result.groupMap())).isEqualTo(longMap(expected.get("cluster_map")));

        var live = state.live();
        var topics = expected.get("topics");
        assertThat(live).hasSize(topics.size());
        for (int p = 0; p < live.size(); p++) {
            var actual = state.topic(live.get(p));
            var want = topics.get(p);
            assertThat(TopicsNightlyFixture.counter(actual.getId())).isEqualTo(want.get("id").asLong());
            assertThat(actual.getName()).isEqualTo(want.get("name").asText());
            assertThat(actual.getDefinition()).isEqualTo(want.get("definition").asText());
            assertThat(actual.getBorn()).hasToString(want.get("born").asText());
            assertThat(actual.getLowNights()).as("low nights of %s", actual.getName())
                    .isEqualTo(want.get("low_nights").asInt());
            assertThat(actual.getLo7()).isEqualTo(want.get("lo7").asInt());
            assertThat(actual.getHi7()).isEqualTo(want.get("hi7").asInt());
            assertThat(actual.getInactiveSince() == null ? null : actual.getInactiveSince().toString())
                    .isEqualTo(want.get("inactive_since").isNull() ? null : want.get("inactive_since").asText());
            assertThat(actual.getSize()).isEqualTo(want.get("size").asInt());
            assertThat(actual.getPrototypes()).hasSize(want.get("prototypes").asInt());
            assertThat(p + 1).isEqualTo(want.get("prompt_id").asInt());
        }
        var retired = new TreeMap<Long, String>();
        state.getTopics().stream().filter(topic -> !topic.isLive()).forEach(topic -> retired.put(
                TopicsNightlyFixture.counter(topic.getId()), topic.getRetiredReason().value()));
        var expectedRetired = new TreeMap<Long, String>();
        expected.get("retired").forEach(topic -> {
            if (topic.get("retired_at").asText().equals(night.date().toString())) {
                expectedRetired.put(topic.get("id").asLong(), topic.get("retired_reason").asText());
            }
        });
        assertThat(retired).isEqualTo(expectedRetired);

        var prototypes = new ArrayList<float[]>();
        live.forEach(t -> prototypes.addAll(state.topic(t).getPrototypes()));
        prototypes.addAll(state.getOtherPrototypes());
        var expectedPrototypes = night.prototypes();
        assertThat(prototypes).hasSize(expectedPrototypes.length / D);
        for (int p = 0; p < prototypes.size(); p++) {
            assertThat(prototypes.get(p)).as("prototype %s", p)
                    .containsExactly(TopicsNightlyFixture.row(expectedPrototypes, p));
        }

        var labels = new long[result.labels().length];
        for (int i = 0; i < labels.length; i++) {
            int label = result.labels()[i];
            labels[i] = label < 0 ? -1 : TopicsNightlyFixture.counter(state.topic(label).getId());
        }
        assertThat(labels).containsExactly(longs(expected.get("final_labels")));

        var shares = new HashMap<Long, Double>();
        result.lifecycleShares().forEach((topic, share) -> shares.put(
                TopicsNightlyFixture.counter(state.topic(topic).getId()), share));
        var expectedShares = new HashMap<Long, Double>();
        expected.get("lifecycle_share").fields().forEachRemaining(entry -> expectedShares.put(
                Long.parseLong(entry.getKey()), entry.getValue().asDouble()));
        assertThat(shares).isEqualTo(expectedShares);
    }

    /** The recorded k-means for the request; the request's points, k, seed and warm start match the stand's. */
    private static KMeans.Result cluster(TopicsNightlyFixture.Night night, NightClusterer.Request request) {
        var name = switch (request.kind()) {
            case RECENTRE_TOPIC -> "recentre_t" + TopicsNightlyFixture.counter(request.topic());
            case RECENTRE_OTHER -> "recentre_other";
            case UNASSIGNED -> "unassigned";
        };
        var record = night.clusters.get(name);
        assertThat(record).as("recorded k-means %s", name).isNotNull();
        assertThat(request.points().texts()).as("%s texts", name)
                .containsExactlyElementsOf(TopicsNightlyFixture.strings(record.get("unique_texts")));
        assertThat(request.points().weights()).as("%s counts", name)
                .containsExactly(TopicsFixture.ints(record.get("unique_counts")));
        assertThat(request.k()).as("%s k", name).isEqualTo(record.get("k").asInt());
        assertThat(request.seed()).as("%s seed", name).isEqualTo(record.get("seed").asLong());
        assertThat(request.init() != null).as("%s warm start", name).isEqualTo(record.get("warm_start").asBoolean());
        return night.cluster(name);
    }

    private static Map<Integer, Long> ids(NightState state, Map<Integer, Integer> map) {
        var out = new HashMap<Integer, Long>();
        map.forEach((group, topic) -> out.put(group,
                topic < 0 ? (long) topic : TopicsNightlyFixture.counter(state.topic(topic).getId())));
        return out;
    }

    private static Map<Integer, Long> longMap(JsonNode node) {
        var out = new HashMap<Integer, Long>();
        node.fields().forEachRemaining(entry -> out.put(Integer.parseInt(entry.getKey()), entry.getValue().asLong()));
        return out;
    }

    private static List<Integer> ints(JsonNode array) {
        var out = new ArrayList<Integer>();
        array.forEach(item -> out.add(item.asInt()));
        return out;
    }

    private static long[] longs(JsonNode array) {
        var out = new long[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asLong();
        }
        return out;
    }

    private static JsonNode copy(JsonNode node) {
        try {
            return InferenceJson.MAPPER.readTree(node.toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
