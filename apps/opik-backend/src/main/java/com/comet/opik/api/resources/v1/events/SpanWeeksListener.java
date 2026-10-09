package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.SpanInsertedByUpdate;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.domain.SpanWeeksDAO;
import com.comet.opik.domain.SpanWeeksDAO.SpanWeek;
import com.comet.opik.utils.WeeklyPartitions;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;
import java.util.Objects;

/**
 * Registers in span_weeks the weekly partition of every span written, so trace-keyed spans reads can bind their
 * partitions. Runs on the AsyncEventBus virtual threads, off the request path; failures are logged and swallowed, since
 * the span itself was already written. A missed registration only leaves a read unbounded until the coverage job
 * re-checks the project.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksListener {

    private final @NonNull SpanWeeksDAO spanWeeksDAO;

    @Subscribe
    public void onSpansCreated(@NonNull SpansCreated event) {
        List<SpanWeek> rows = event.spans().stream()
                .filter(span -> span.projectId() != null && span.traceId() != null && span.id() != null)
                .map(SpanWeeksListener::toSpanWeek)
                .distinct()
                .toList();
        insert(rows, event.workspaceId(), event.userName());
    }

    @Subscribe
    public void onSpanInsertedByUpdate(@NonNull SpanInsertedByUpdate event) {
        insert(List.of(SpanWeek.builder()
                .projectId(event.projectId())
                .traceId(event.spanTraceId())
                .idWeek(WeeklyPartitions.storedPartitionOf(event.spanId()))
                .build()), event.workspaceId(), event.userName());
    }

    private static SpanWeek toSpanWeek(Span span) {
        return SpanWeek.builder()
                .projectId(span.projectId())
                .traceId(span.traceId())
                .idWeek(WeeklyPartitions.storedPartitionOf(Objects.requireNonNull(span.id())))
                .build();
    }

    private void insert(List<SpanWeek> rows, String workspaceId, String userName) {
        spanWeeksDAO.insert(rows, workspaceId, userName)
                .subscribe(
                        null,
                        error -> log.error("Failed to register '{}' span weeks for workspace '{}'", rows.size(),
                                workspaceId, error));
    }
}
