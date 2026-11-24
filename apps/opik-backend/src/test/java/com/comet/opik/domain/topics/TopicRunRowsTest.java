package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("topic run rows")
class TopicRunRowsTest {

    private static final int D = TopicEmbedding.DIMENSIONS;
    private static final TopicRun RUN = TopicRun.of(new UUID(9, 1), "workspace", "Workspace", new UUID(9, 2),
            LocalDate.of(2026, 11, 20), 30);
    private static final List<WindowTrace> TRACES = List.of(
            new WindowTrace(new UUID(1, 0), Instant.parse("2026-11-01T00:00:00Z")),
            new WindowTrace(new UUID(1, 1), Instant.parse("2026-11-01T00:00:01Z")),
            new WindowTrace(new UUID(1, 2), Instant.parse("2026-11-01T00:00:02Z")),
            new WindowTrace(new UUID(1, 3), Instant.parse("2026-11-01T00:00:03Z")));

    @Test
    @DisplayName("each topic gets an id, its size and its vectors; each trace a row with its topic or Other; the stats "
            + "count the topics, the Other share and the prototypes")
    void rows() {
        var built = built(new int[]{0, 1, 0, TopicAlgorithm.OTHER}, List.of(unit(2)));

        var rows = TopicRunRows.of(RUN, TRACES, built, ids());

        assertThat(rows.topics()).extracting(Topic::id, Topic::name, Topic::size, Topic::born)
                .containsExactly(
                        tuple(new UUID(7, 1), "First", 2, RUN.born()),
                        tuple(new UUID(7, 2), "Second", 1, RUN.born()));
        assertThat(rows.otherVectors()).isEqualTo(new PrototypeVectors(List.of(unit(2))));
        assertThat(rows.traceTopics()).extracting(TraceTopic::traceId, TraceTopic::topicId)
                .containsExactly(
                        tuple(new UUID(1, 0), new UUID(7, 1)),
                        tuple(new UUID(1, 1), new UUID(7, 2)),
                        tuple(new UUID(1, 2), new UUID(7, 1)),
                        tuple(new UUID(1, 3), TraceTopic.OTHER));
        assertThat(rows.traceTopics()).allSatisfy(row -> {
            assertThat(row.runId()).isEqualTo(RUN.runId());
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
        });
        assertThat(rows.otherShare()).isEqualTo(0.25);
        assertThat(rows.stats().get("topics").asInt()).isEqualTo(2);
        assertThat(rows.stats().get("prototypes").asInt()).isEqualTo(3);
        assertThat(rows.stats().get("algorithm_stat").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("no known-Other prototype saves no Other vectors, and a discarded run keeps no trace rows")
    void noOtherVectorsAndDiscarded() {
        var rows = TopicRunRows.of(RUN, TRACES, built(new int[]{0, 0, 1, 1}, List.of()), ids());

        assertThat(rows.otherVectors()).isNull();
        assertThat(rows.otherShare()).isZero();
        assertThat(rows.discarded().traceTopics()).isEmpty();
        assertThat(rows.discarded().topics()).isEqualTo(rows.topics());
    }

    @Test
    @DisplayName("a list that labels another number of traces than the window has cannot be mapped")
    void labelsMustMatchTheWindow() {
        assertThatThrownBy(() -> TopicRunRows.of(RUN, TRACES, built(new int[]{0, 1}, List.of()), ids()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FirstBuild built(int[] labels, List<float[]> otherPrototypes) {
        var stats = JsonUtils.createObjectNode();
        stats.put("algorithm_stat", 7);
        return FirstBuild.builder()
                .topics(List.of(
                        NewTopic.builder().name("First").definition("one").prototypes(List.of(unit(0))).build(),
                        NewTopic.builder().name("Second").definition("two").prototypes(List.of(unit(1))).build()))
                .labels(labels)
                .otherPrototypes(new ArrayList<>(otherPrototypes))
                .stats(stats)
                .build();
    }

    private static Supplier<UUID> ids() {
        var next = new AtomicLong();
        return () -> new UUID(7, next.incrementAndGet());
    }

    static float[] unit(int axis) {
        var vector = new float[D];
        vector[axis] = 1;
        return vector;
    }
}
