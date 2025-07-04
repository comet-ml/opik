package com.comet.opik.api.resources.v1.events;

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

        Map<UUID, Map<String, Instant>> projectThreadIds = new HashMap<>();

        event.traces().forEach(trace -> {
            UUID projectId = trace.projectId();
            String threadId = trace.threadId();

            if (StringUtils.isNotBlank(threadId)) {
                Map<String, Instant> threadIdAndLastUpdatedAt = projectThreadIds
                        .computeIfAbsent(projectId, id -> new HashMap<>());

                // Keeps the most recent lastUpdatedAt for each threadId
                threadIdAndLastUpdatedAt.computeIfPresent(threadId,
                        (id, currentTime) -> {
                            Instant newTime = Optional.ofNullable(trace.lastUpdatedAt())
                                    .orElseGet(Instant::now);
                            return newTime.isAfter(currentTime) ? newTime : currentTime;
                        });

                // If the threadId is not present, add it with the lastUpdatedAt
                threadIdAndLastUpdatedAt.computeIfAbsent(threadId, id -> Optional.ofNullable(trace.lastUpdatedAt())
                        .orElseGet(Instant::now));

            }
        });

        processEvent(event, projectThreadIds)
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
            Map<UUID, Map<String, Instant>> projectThreadIdAndLastUpdateAts) {

        return Flux.fromIterable(projectThreadIdAndLastUpdateAts.entrySet())
                .flatMap(entry -> {
                    UUID projectId = entry.getKey();
                    Map<String, Instant> threadIdAndLastUpdateAts = entry.getValue();
                    return processProjectTraceThread(event, projectId, threadIdAndLastUpdateAts);
                });
    }

    private Mono<Void> processProjectTraceThread(TracesCreated event, UUID projectId,
            Map<String, Instant> threadIdAndLastUpdateAts) {
        log.info("Processing trace threads for workspace: '{}', projectId: '{}', threadIds: '[{}]'",
                event.workspaceId(), projectId, threadIdAndLastUpdateAts.keySet());
        return traceThreadService.processTraceThreads(threadIdAndLastUpdateAts, projectId);
    }

    /**
     * Handles the ThreadsReopened event by deleting manual scores for the specified threads.
     * This is triggered when threads are reopened, and it ensures that any manual scores
     * associated with those threads are removed.
     *
     * @param event the ThreadsReopened event containing the thread model IDs and project ID
     */
    @Subscribe
    public void onThreadsReopened(@NonNull ThreadsReopened event) {
        log.info("Received ThreadsReopened event for workspace: '{}', projectId: '{}', threadModelIds: '[{}]'",
                event.workspaceId(), event.projectId(), event.threadModelIds());

        feedbackScoreService.deleteThreadManualScores(event.threadModelIds(), event.projectId())
                .doOnError(error -> {
                    log.info(
                            "Failed to delete manual scores for threads in workspace: '{}', projectId: '{}'",
                            event.workspaceId(), event.projectId());
                    log.error("Error deleting manual scores for threads", error);
                })
                .doOnSuccess(unused -> log.info("Deleted manual scores for threads in workspace: '{}', projectId: '{}'",
                        event.workspaceId(), event.projectId()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }

}
