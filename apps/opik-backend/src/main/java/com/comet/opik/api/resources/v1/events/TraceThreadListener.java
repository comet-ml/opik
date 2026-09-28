package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ThreadTimestamps;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceThreadListener {

    private final @NonNull TraceThreadService traceThreadService;

    /**
     * Handles the TracesCreated event by processing trace threads in a thread-safe manner.
     * It groups trace threads by project ID and processes them with a lock to ensure
     * that no two processes handle the same project threads concurrently.
     *
     * @param event the TracesCreated event containing the traces to process
     */
    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        log.info(
                "Received TracesCreated event for workspace: '{}', projectIds: '[{}]'. Processing trace thread ingestion",
                event.workspaceId(),
                event.projectIds());

        Map<UUID, Map<String, ThreadTimestamps>> projectThreadInfo = new HashMap<>();

        event.traces().forEach(trace -> {
            UUID projectId = trace.projectId();
            String threadId = trace.threadId();

            if (StringUtils.isNotBlank(threadId)) {
                Map<String, ThreadTimestamps> threadInfo = projectThreadInfo
                        .computeIfAbsent(projectId, id -> new HashMap<>());

                // Track both the minimum trace ID (for thread model ID generation)
                // and the most recent lastUpdatedAt (for thread updates)
                threadInfo.compute(threadId, (id, existing) -> {
                    Instant lastUpdatedAt = Optional.ofNullable(trace.lastUpdatedAt())
                            .orElseGet(Instant::now);
                    UUID traceId = trace.id();

                    if (existing == null) {
                        return ThreadTimestamps.builder()
                                .firstTraceId(traceId)
                                .maxLastUpdatedAt(lastUpdatedAt)
                                .firstTraceSource(trace.source())
                                .firstTraceEnvironment(trace.environment())
                                .build();
                    }

                    // Keep the minimum trace ID (earliest timestamp in UUIDv7)
                    // Note: UUIDv7 compareTo() works correctly here because UUID v7 are
                    // lexicographically ordered by their timestamp component
                    UUID minTraceId = traceId.compareTo(existing.firstTraceId()) < 0
                            ? traceId
                            : existing.firstTraceId();

                    // Keep the most recent lastUpdatedAt
                    var maxLastUpdatedAt = lastUpdatedAt.isAfter(existing.maxLastUpdatedAt())
                            ? lastUpdatedAt
                            : existing.maxLastUpdatedAt();

                    // Track the source from the chronologically earliest trace (matching firstTraceId).
                    // In practice traces in a thread originate from the same source, but this is not enforced.
                    boolean isEarlier = traceId.compareTo(existing.firstTraceId()) < 0;
                    var earliestSource = isEarlier ? trace.source() : existing.firstTraceSource();
                    var earliestEnvironment = isEarlier ? trace.environment() : existing.firstTraceEnvironment();

                    return existing.toBuilder()
                            .firstTraceId(minTraceId)
                            .maxLastUpdatedAt(maxLastUpdatedAt)
                            .firstTraceSource(earliestSource)
                            .firstTraceEnvironment(earliestEnvironment)
                            .build();
                });
            }
        });

        processEvent(event, projectThreadInfo)
                .doOnError(error -> {
                    log.error(
                            "Fail to process TracesCreated event for workspace: '{}', projectIds: '{}', error: '{}'",
                            event.workspaceId(), event.projectIds(), error.getMessage());
                    log.error("Error processing trace thread ingestion", error);
                })
                .doOnComplete(
                        () -> log.info("Completed processing TracesCreated event for workspace: '{}', projectIds: '{}'",
                                event.workspaceId(), event.projectIds()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }

    /**
     * Registers the thread in trace_threads when a trace update sets a thread id.
     *
     * The trace UPDATE overwrites traces.thread_id, but only trace creation registers the thread, so a thread id first
     * set by a PATCH had no trace_threads row. The Threads list inner-joins that table whenever a time range is set,
     * so such a thread was missing from the list while direct-open, which left-joins, still resolved it.
     *
     * The thread row is built from the event rather than read back from traces: a read-after-write on a lagging
     * replica could return nothing and drop the thread for good, since the closing job only acts on existing rows.
     * Going through the creation path also derives the thread model id from the earliest trace id and reopens a thread
     * that was already closed, as a POST into it would.
     *
     * The thread the traces moved away from is intentionally left untouched: it keeps its own traces and is closed by
     * the regular closing job.
     *
     * @param event the TracesUpdated event whose update may carry a thread id
     */
    @Subscribe
    public void onTracesUpdated(@NonNull TracesUpdated event) {
        TraceUpdate traceUpdate = event.traceUpdate();
        String threadId = traceUpdate.threadId();

        if (StringUtils.isBlank(threadId)) {
            return;
        }

        log.info("Received TracesUpdated event for workspace: '{}', projectIds: '[{}]' with threadId set. "
                + "Processing trace thread registration", event.workspaceId(), event.projectIds());

        Map<UUID, Set<UUID>> traceIdsByProject = Optional.ofNullable(event.traceIdToProjectId())
                .map(traceIdToProjectId -> event.traceIds().stream()
                        .filter(traceIdToProjectId::containsKey)
                        .collect(Collectors.groupingBy(traceIdToProjectId::get, Collectors.toSet())))
                .filter(grouped -> !grouped.isEmpty())
                .orElseGet(() -> event.projectIds().stream()
                        .collect(Collectors.toMap(Function.identity(), projectId -> event.traceIds())));

        Flux.fromIterable(traceIdsByProject.entrySet())
                .flatMap(entry -> {
                    // UUIDv7 ordering is chronological, so the minimum trace id carries the earliest timestamp.
                    var timestamps = ThreadTimestamps.builder()
                            .firstTraceId(Collections.min(entry.getValue()))
                            .maxLastUpdatedAt(Instant.now())
                            .firstTraceSource(traceUpdate.source())
                            .firstTraceEnvironment(traceUpdate.environment())
                            .build();
                    return traceThreadService.processTraceThreads(Map.of(threadId, timestamps), entry.getKey());
                })
                .doOnError(error -> log.error(
                        "Fail to process TracesUpdated event for workspace: '{}', projectIds: '{}'",
                        event.workspaceId(), traceIdsByProject.keySet(), error))
                .doOnComplete(() -> log.info(
                        "Completed processing TracesUpdated event for workspace: '{}', projectIds: '{}'",
                        event.workspaceId(), traceIdsByProject.keySet()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }

    private Flux<Void> processEvent(TracesCreated event,
            Map<UUID, Map<String, ThreadTimestamps>> projectThreadInfo) {

        return Flux.fromIterable(projectThreadInfo.entrySet())
                .flatMap(entry -> {
                    UUID projectId = entry.getKey();
                    Map<String, ThreadTimestamps> threadInfo = entry.getValue();
                    return processProjectTraceThread(event, projectId, threadInfo);
                });
    }

    private Mono<Void> processProjectTraceThread(TracesCreated event, UUID projectId,
            Map<String, ThreadTimestamps> threadInfo) {
        log.info("Processing trace threads for workspace: '{}', projectId: '{}', threadIds: '[{}]'",
                event.workspaceId(), projectId, threadInfo.keySet());
        return traceThreadService.processTraceThreads(threadInfo, projectId);
    }

}
