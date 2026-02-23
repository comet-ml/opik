package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ThreadTimestamps;
import com.comet.opik.api.events.ThreadsReopened;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.FeedbackScoreService;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceThreadListener {

    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull FeedbackScoreService feedbackScoreService;

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
                        return new ThreadTimestamps(traceId, lastUpdatedAt);
                    }

                    // Keep the minimum trace ID (earliest timestamp in UUIDv7)
                    // Note: UUIDv7 compareTo() works correctly here because UUIDv7s are
                    // lexicographically ordered by their timestamp component
                    UUID minTraceId = traceId.compareTo(existing.firstTraceId()) < 0
                            ? traceId
                            : existing.firstTraceId();

                    // Keep the most recent lastUpdatedAt
                    Instant maxLastUpdatedAt = lastUpdatedAt.isAfter(existing.lastUpdatedAt())
                            ? lastUpdatedAt
                            : existing.lastUpdatedAt();

                    return new ThreadTimestamps(minTraceId, maxLastUpdatedAt);
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

    /**
     * Handles the ThreadsReopened event by deleting all scores for the specified threads.
     * This is triggered in two scenarios:
     * 1. When new traces are added to an existing thread (thread is "reopened" for activity)
     * 2. When the explicit PUT /threads/open API endpoint is called
     *
     * The handler ensures that all scores (manual UI, SDK, and online scoring) are removed
     * so the thread can be re-evaluated after the cooling period expires.
     *
     * Note: While the active/inactive thread status concept has been hidden from the UI,
     * the status still exists internally. This cleanup behavior is preserved to maintain
     * data consistency. The thread will be re-evaluated using its original sampling decision
     * after the cooling period expires.
     *
     * @param event the ThreadsReopened event containing the thread model IDs and project ID
     */
    @Subscribe
    public void onThreadsReopened(@NonNull ThreadsReopened event) {
        log.info("Received ThreadsReopened event for workspace: '{}', projectId: '{}', threadModelIds: '[{}]'",
                event.workspaceId(), event.projectId(), event.threadModelIds());

        feedbackScoreService.deleteAllThreadScores(event.threadModelIds(), event.projectId())
                .doOnError(error -> {
                    log.error(
                            "Error deleting all scores for threads in workspace: '{}', projectId: '{}', threadModelIds: '[{}]'",
                            event.workspaceId(), event.projectId(), event.threadModelIds(), error);
                })
                .doOnSuccess(unused -> log.info("Deleted all scores for threads in workspace: '{}', projectId: '{}'",
                        event.workspaceId(), event.projectId()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }

}
