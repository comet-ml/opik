package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("night rows")
class NightRowsTest {

    private static final int OTHER = TopicAlgorithm.OTHER;
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 20);
    private static final TopicRun RUN = TopicRun.of(new UUID(9, 1), "workspace", "Workspace", new UUID(9, 2), NIGHT,
            30);
    private static final List<WindowTrace> TRACES = List.of(
            new WindowTrace(new UUID(1, 0), Instant.parse("2026-11-01T00:00:00Z")),
            new WindowTrace(new UUID(1, 1), Instant.parse("2026-11-01T00:00:01Z")),
            new WindowTrace(new UUID(1, 2), Instant.parse("2026-11-01T00:00:02Z")),
            new WindowTrace(new UUID(1, 3), Instant.parse("2026-11-01T00:00:03Z")));
    private static final UUID KEPT = new UUID(7, 1);
    private static final UUID RETIRED = new UUID(7, 2);
    private static final UUID BORN = new UUID(7, 3);

    @Test
    @DisplayName("a stored live topic is updated, a topic born tonight inserted and a stored retired one deleted; the "
            + "stats count the births, the retired by reason, the live "
            + "topics, the Other share and the churn")
    void rows() {
        var state = state(true);
        var list = new NightList(state, new UUID(8, 1), Set.of(KEPT, RETIRED));

        var rows = NightRows.of(RUN, TRACES, list, 2, update(new int[]{0, 0, OTHER, 0}, new int[]{0, 2, OTHER, 0}));

        assertThat(rows.save().keep()).isTrue();
        assertThat(rows.save().updated()).extracting(Topic::id, Topic::size, Topic::lowNights)
                .containsExactly(tuple(KEPT, 2, 1));
        assertThat(rows.save().inserted()).extracting(Topic::id, Topic::born).containsExactly(tuple(BORN, NIGHT));
        assertThat(rows.save().retired()).containsExactly(RETIRED);
        assertThat(rows.retired()).extracting(NightTopic::getId).containsExactly(RETIRED);
        assertThat(rows.save().otherVectors()).isEqualTo(new PrototypeVectors(List.of(TopicsTestData.unit(5))));
        assertThat(rows.born()).isEqualTo(1);
        assertThat(rows.live()).isEqualTo(2);
        assertThat(rows.otherShare()).isEqualTo(0.25);
        assertThat(rows.churn()).isEqualTo(TopicChurn.of(new int[]{0, 0, OTHER, 0}, new int[]{0, 2, OTHER, 0}));
        var stats = rows.stats();
        assertThat(stats.get("algorithm_stat").asInt()).isEqualTo(7);
        assertThat(stats.get("topics_before").asInt()).isEqualTo(2);
        assertThat(stats.get("born").asInt()).isEqualTo(1);
        assertThat(stats.get("topics").asInt()).isEqualTo(2);
        assertThat(stats.get("retired").get("low_share").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a night that leaves no live topic clears the list: no Other vectors")
    void noLiveTopicClearsTheList() {
        var state = state(false);
        var list = new NightList(state, new UUID(8, 1), Set.of(KEPT, RETIRED));

        var rows = NightRows.of(RUN, TRACES, list, 2, update(new int[]{0, 0, 0, 0}, new int[]{OTHER, OTHER, OTHER,
                OTHER}));

        assertThat(rows.save().keep()).isFalse();
        assertThat(rows.save().retired()).containsExactlyInAnyOrder(KEPT, RETIRED);
        assertThat(rows.save().updated()).isEmpty();
        assertThat(rows.save().otherVectors()).isNull();
        assertThat(rows.live()).isZero();
    }

    @Test
    @DisplayName("labels for another number of traces than the window has cannot be mapped")
    void labelsMustMatchTheWindow() {
        var list = new NightList(state(true), new UUID(8, 1), Set.of(KEPT, RETIRED));

        assertThatThrownBy(() -> NightRows.of(RUN, TRACES, list, 2, update(new int[]{0}, new int[]{0})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Kept (stored), retired (stored) and, when {@code live}, born tonight; otherwise every topic is retired. */
    private static NightState state(boolean live) {
        var kept = NightTopic.builder().id(KEPT).name("Kept").definition("K.").born(NIGHT.minusDays(9))
                .prototypes(List.of(TopicsTestData.unit(0))).lowNights(1).size(2);
        var topics = live
                ? List.of(kept.build(), retired(), NightTopic.builder().id(BORN).name("Born").definition("B.")
                        .born(NIGHT).prototypes(List.of(TopicsTestData.unit(2))).size(1).build())
                : List.of(kept.retiredReason(NightState.RetiredReason.LOW_SHARE).build(), retired());
        return NightState.builder().topics(topics).otherPrototypes(List.of(TopicsTestData.unit(5))).rebuilds(1)
                .night(NIGHT).ids(() -> {
                    throw new AssertionError("no id is made");
                }).build();
    }

    private static NightTopic retired() {
        return NightTopic.builder().id(RETIRED).name("Retired").definition("R.").born(NIGHT.minusDays(9))
                .prototypes(List.of(TopicsTestData.unit(1))).retiredReason(NightState.RetiredReason.LOW_SHARE)
                .build();
    }

    private static TopicUpdate update(int[] before, int[] labels) {
        var stats = JsonUtils.createObjectNode();
        stats.put("algorithm_stat", 7);
        return new TopicUpdate(before, labels, stats);
    }
}
