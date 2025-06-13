package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesCreated;
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
import java.util.UUID;

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
    public void onTracesCreated(TracesCreated event) {
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

                threadIdAndLastUpdatedAt.computeIfPresent(threadId,
                        (id, existingTime) -> trace.lastUpdatedAt().isAfter(existingTime)
                                ? trace.lastUpdatedAt()
                                : existingTime);

                threadIdAndLastUpdatedAt.computeIfAbsent(threadId, id -> trace.lastUpdatedAt());
            }
        });

        processEvent(event, projectThreadIds)
                .doOnError(error -> {
                    log.error(
                            "Fail to process TracesCreated event for workspace: '{}', projectIds: '[{}]', error: '{}'",
                            event.workspaceId(), event.projectIds(), error.getMessage());
                    log.error("Error processing trace thread ingestion", error);
                })
                .doOnComplete(
                        () -> log.info("Completed processing TracesCreated event for workspace: '{}', projectIds: '{}'",
                                event.workspaceId(), event.projectIds()))
                .subscribe();
    }

    private Flux<Void> processEvent(TracesCreated event,
            Map<UUID, Map<String, Instant>> projectThreadIdAndLastUpdateAts) {
        return Flux.fromIterable(projectThreadIdAndLastUpdateAts.entrySet())
                .flatMap(entry -> {
                    UUID projectId = entry.getKey();
                    Map<String, Instant> threadIdAndLastUpdateAts = entry.getValue();

                    return processProjectTraceThread(event, projectId, threadIdAndLastUpdateAts);
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()));
    }

    private Mono<Void> processProjectTraceThread(TracesCreated event, UUID projectId,
            Map<String, Instant> threadIdAndLastUpdateAts) {

        log.info("Processing trace threads for workspace: '{}', projectId: '{}', threadIds: '[{}]'",
                event.workspaceId(), projectId, threadIdAndLastUpdateAts.keySet());

        return traceThreadService.processTraceThreads(threadIdAndLastUpdateAts, projectId);
    }

}
