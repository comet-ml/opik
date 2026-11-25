package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("topic window builder")
class TopicWindowBuilderTest {

    private static final int D = TopicEmbedding.DIMENSIONS;
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    @ParameterizedTest(name = "capacity {0}")
    @ValueSource(ints = {1, 20, 1_000})
    @DisplayName("the window comes out in time order, each text with its own vector and trace, whether the arrays grow, "
            + "fit or are cut; a blank text and a short vector are skipped")
    void timeOrderWhateverTheCapacity(int capacity) {
        var rows = new ArrayList<TopicWindowDAO.WindowRow>();
        for (int i = 0; i < 20; i++) {
            rows.add(row(new UUID(0, i), START.plusSeconds(i), "text " + i, i, 1));
        }
        rows.add(row(new UUID(1, 0), START, "  ", 0, 1));
        rows.add(row(new UUID(1, 1), START, "short", 0, 1).toBuilder().vector(new float[D - 1]).build());
        Collections.shuffle(rows, new Random(capacity));

        var window = build(capacity, rows, 20);

        assertThat(window.texts()).hasSize(20);
        assertThat(window.vectors()).hasSize(20 * D);
        for (int i = 0; i < 20; i++) {
            assertThat(window.texts()[i]).isEqualTo("text " + i);
            assertThat(window.vectors()[i * D]).isEqualTo(i);
            assertThat(window.traces().get(i)).isEqualTo(new WindowTrace(new UUID(0, i), START.plusSeconds(i)));
        }
        assertThat(window.stats().get("window_rows").asInt()).isEqualTo(22);
        assertThat(window.stats().get("skipped_rows").asInt()).isEqualTo(2);
        assertThat(window.stats().get("vectors").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("traces created at the same instant are ordered by trace id")
    void tiesOnCreatedAtGoByTraceId() {
        var window = build(3, List.of(row(new UUID(0, 2), START, "c", 2, 1), row(new UUID(0, 0), START, "a", 0, 1),
                row(new UUID(0, 1), START, "b", 1, 1)), 3);

        assertThat(window.texts()).containsExactly("a", "b", "c");
        assertThat(window.traces()).extracting(WindowTrace::traceId)
                .containsExactly(new UUID(0, 0), new UUID(0, 1), new UUID(0, 2));
    }

    @Test
    @DisplayName("the stats carry each task version's share and mark a window below provisionalBelow provisional")
    void stats() {
        var window = new TopicWindowBuilder(4);
        window.add(row(new UUID(0, 0), START, "a", 0, 1));
        window.add(row(new UUID(0, 1), START.plusSeconds(1), "b", 1, 1));
        window.add(row(new UUID(0, 2), START.plusSeconds(2), "c", 2, 1));
        window.add(row(new UUID(0, 3), START.plusSeconds(3), "d", 3, 2));

        var stats = window.build(500, 5).stats();

        assertThat(stats.get("window_traces").asLong()).isEqualTo(500);
        assertThat(stats.get("task_version_shares").get("1").asDouble()).isEqualTo(0.75);
        assertThat(stats.get("task_version_shares").get("2").asDouble()).isEqualTo(0.25);
        assertThat(stats.get("provisional").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a window with no usable row fails with window_read")
    void noUsableRow() {
        var window = new TopicWindowBuilder(1);
        window.add(row(new UUID(0, 0), START, " ", 0, 1));

        assertThatThrownBy(() -> window.build(1, 1)).isInstanceOfSatisfying(TopicsRunException.class,
                error -> assertThat(error.getCode()).isEqualTo(TopicProject.RunError.WINDOW_READ));
    }

    @Test
    @DisplayName("permuteRows moves row order[i] to row i in place, across several cycles")
    void permuteRows() {
        int[] order = {2, 0, 1, 4, 3, 5};
        var vectors = new float[order.length * D];
        for (int i = 0; i < order.length; i++) {
            vectors[i * D] = i;
            vectors[i * D + D - 1] = -i;
        }

        TopicWindowBuilder.permuteRows(vectors, order);

        for (int i = 0; i < order.length; i++) {
            assertThat(vectors[i * D]).isEqualTo(order[i]);
            assertThat(vectors[i * D + D - 1]).isEqualTo(-order[i]);
        }
    }

    private static RunWindow build(int capacity, List<TopicWindowDAO.WindowRow> rows, long windowTraces) {
        var builder = new TopicWindowBuilder(capacity);
        rows.forEach(builder::add);
        return builder.build(windowTraces, 1);
    }

    private static TopicWindowDAO.WindowRow row(UUID traceId, Instant createdAt, String text, int marker,
            int taskVersion) {
        var vector = new float[D];
        vector[0] = marker;
        return TopicWindowDAO.WindowRow.builder()
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .taskVersion(taskVersion)
                .text(text)
                .vector(vector)
                .build();
    }
}
