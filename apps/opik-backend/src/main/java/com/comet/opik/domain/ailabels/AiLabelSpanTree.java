package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a trace's span tree. Started as a copy of {@code OnlineScoringEngine.buildSpanTree} and kept apart from it on
 * purpose, so the two can diverge without online scoring changing.
 *
 * <p>Siblings are ordered by ({@code start_time}, id) at every level: the id makes the order total, so the same spans
 * always give the same tree whatever order they arrive in.
 *
 * <p>Every span is rendered exactly once. A span that can't be reached from a root is attached to the root list rather
 * than dropped: its parent is missing (cut by the per-trace span cap, or never written) or its parent chain is a
 * cycle, which is broken at its earliest span.
 */
@UtilityClass
class AiLabelSpanTree {

    private static final Comparator<SpanRow> SIBLING_ORDER = Comparator
            .comparing(SpanRow::startTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SpanRow::id);

    static List<AiLabelSpan> build(@NonNull List<SpanRow> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }
        Map<String, List<SpanRow>> childrenByParent = new HashMap<>();
        Set<String> presentIds = new HashSet<>();
        for (SpanRow span : spans) {
            presentIds.add(span.id());
            if (span.parentSpanId() != null) {
                childrenByParent.computeIfAbsent(span.parentSpanId(), k -> new ArrayList<>()).add(span);
            }
        }
        childrenByParent.values().forEach(children -> children.sort(SIBLING_ORDER));

        List<SpanRow> roots = new ArrayList<>(spans.stream()
                .filter(span -> span.parentSpanId() == null || !presentIds.contains(span.parentSpanId()))
                .toList());
        Set<String> reached = new HashSet<>();
        roots.forEach(root -> reach(root, childrenByParent, reached));
        if (reached.size() < presentIds.size()) {
            spans.stream()
                    .sorted(SIBLING_ORDER)
                    .filter(span -> !reached.contains(span.id()))
                    .forEach(span -> {
                        if (!reached.contains(span.id())) {
                            roots.add(span);
                            reach(span, childrenByParent, reached);
                        }
                    });
        }
        roots.sort(SIBLING_ORDER);

        Set<String> built = new HashSet<>();
        return roots.stream()
                .map(root -> buildSpanNode(root, childrenByParent, built))
                .toList();
    }

    private static void reach(SpanRow from, Map<String, List<SpanRow>> childrenByParent, Set<String> reached) {
        var pending = new ArrayDeque<SpanRow>();
        pending.push(from);
        while (!pending.isEmpty()) {
            var span = pending.pop();
            if (reached.add(span.id())) {
                childrenByParent.getOrDefault(span.id(), List.of()).forEach(pending::push);
            }
        }
    }

    private static AiLabelSpan buildSpanNode(SpanRow span, Map<String, List<SpanRow>> childrenByParent,
            Set<String> built) {
        built.add(span.id());
        List<AiLabelSpan> children = childrenByParent.getOrDefault(span.id(), List.of()).stream()
                // A child already built closes a cycle back to where it was broken.
                .filter(child -> !built.contains(child.id()))
                .map(child -> buildSpanNode(child, childrenByParent, built))
                .toList();
        return AiLabelSpan.builder()
                .name(StringUtils.defaultIfBlank(span.name(), null))
                .type(SpanType.fromString(span.type()))
                .startTime(span.startTime())
                .endTime(span.endTime())
                .duration(durationMillis(span.startTime(), span.endTime()))
                .input(AiLabelJson.parse(span.input()))
                .output(AiLabelJson.parse(span.output()))
                .metadata(AiLabelJson.parse(span.metadata()))
                .model(span.model())
                .provider(span.provider())
                .errorInfo(AiLabelJson.parse(span.errorInfo()))
                .spans(children.isEmpty() ? null : children)
                .build();
    }

    /** Milliseconds with microsecond precision, as the {@code duration} column computes it. */
    static Double durationMillis(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || Instant.EPOCH.equals(startTime)) {
            return null;
        }
        var elapsed = Duration.between(startTime, endTime);
        return (elapsed.getSeconds() * 1_000_000L + elapsed.getNano() / 1_000) / 1000.0;
    }
}
