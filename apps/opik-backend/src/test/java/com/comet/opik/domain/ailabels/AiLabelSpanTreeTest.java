package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AiLabelSpanTreeTest {

    private static final UUID TRACE_ID = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void buildOrdersSiblingsByStartTimeThenId() {
        var spans = List.of(
                span("b", null, 1),
                span("a", null, 1),
                span("c", null, 0),
                span("b2", "b", 5),
                span("b1", "b", 5),
                span("b0", "b", 9));

        var tree = AiLabelSpanTree.build(spans);

        assertThat(names(tree)).containsExactly("c", "a", "b");
        assertThat(names(tree.getLast().spans())).containsExactly("b1", "b2", "b0");
    }

    @Test
    void buildAttachesSpansWithAMissingParentToTheRootInStartOrder() {
        var spans = List.of(
                span("root", null, 0),
                span("child", "root", 1),
                span("orphan-late", "cut-off", 7),
                span("orphan-early", "cut-off", 2),
                span("orphan-child", "orphan-early", 3));

        var tree = AiLabelSpanTree.build(spans);

        assertThat(names(tree)).containsExactly("root", "orphan-early", "orphan-late");
        assertThat(names(tree.get(1).spans())).containsExactly("orphan-child");
    }

    @Test
    void buildBreaksAParentCycleAtItsEarliestSpan() {
        var spans = List.of(
                span("root", null, 0),
                span("x", "z", 4),
                span("y", "x", 5),
                span("z", "y", 3),
                span("self", "self", 9));

        var tree = AiLabelSpanTree.build(spans);

        assertThat(names(tree)).containsExactly("root", "z", "self");
        assertThat(names(tree.get(1).spans())).containsExactly("x");
        assertThat(names(tree.get(1).spans().getFirst().spans())).containsExactly("y");
        assertThat(tree.get(1).spans().getFirst().spans().getFirst().spans()).isNull();
        assertThat(tree.get(2).spans()).isNull();
    }

    @Test
    void buildRendersEverySpanOnce() {
        var fixtures = new AiLabelRenderFixtures(3, false);
        var trace = fixtures.trace(10);
        var spans = new ArrayList<>(fixtures.spans(trace, 500, 20, 5));
        // Drop a few parents, as the per-trace span cap does.
        spans.subList(0, 50).clear();

        assertThat(flatten(AiLabelSpanTree.build(spans)).map(AiLabelSpan::name))
                .containsExactlyInAnyOrderElementsOf(spans.stream().map(SpanRow::name).toList());
    }

    @Test
    void buildHandlesAChainAsDeepAsTheSpanCap() {
        var chain = IntStream.range(0, 1_000)
                .mapToObj(i -> span("s" + i, i == 0 ? null : "s" + (i - 1), i))
                .toList();

        var names = new ArrayList<String>();
        var level = AiLabelSpanTree.build(chain);
        while (level != null) {
            assertThat(level).hasSize(1);
            names.add(level.getFirst().name());
            level = level.getFirst().spans();
        }

        // One span per level, each the child of the one before.
        assertThat(names).containsExactlyElementsOf(chain.stream().map(SpanRow::name).toList());
    }

    private static Stream<AiLabelSpan> flatten(List<AiLabelSpan> spans) {
        return Stream.ofNullable(spans).flatMap(List::stream).flatMap(span -> Stream.concat(Stream.of(span),
                flatten(span.spans())));
    }

    private static List<String> names(List<AiLabelSpan> spans) {
        return Stream.ofNullable(spans).flatMap(List::stream).map(AiLabelSpan::name).toList();
    }

    private static SpanRow span(String id, String parentSpanId, int startSecond) {
        return SpanRow.builder()
                .traceId(TRACE_ID)
                .id(id)
                .parentSpanId(parentSpanId)
                .name(id)
                .type("general")
                .startTime(T0.plusSeconds(startSecond))
                .build();
    }
}
