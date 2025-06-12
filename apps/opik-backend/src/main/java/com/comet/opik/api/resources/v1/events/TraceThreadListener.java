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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        log.info("Received TracesCreated event for workspace: {}, projectIds: {}", event.workspaceId(),
                event.projectIds());

        Map<UUID, Set<String>> projectThreadIds = new HashMap<>();

        event.traces().forEach(trace -> {
            UUID projectId = trace.projectId();
            String threadId = trace.threadId();

            if (StringUtils.isNotBlank(threadId)) {
                projectThreadIds.computeIfAbsent(projectId, key -> new HashSet<>()).add(threadId);
            }
        });

        processEvent(event, projectThreadIds).blockLast();
    }

    private Flux<Void> processEvent(TracesCreated event, Map<UUID, Set<String>> projectThreadIds) {
        return Flux.fromIterable(projectThreadIds.entrySet())
                .flatMap(entry -> {
                    UUID projectId = entry.getKey();
                    Set<String> threadIds = entry.getValue();

                    return processProjectTraceThread(event, projectId, threadIds);
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()));
    }

    private Mono<Void> processProjectTraceThread(TracesCreated event, UUID projectId, Set<String> threadIds) {

        log.info("Processing trace threads for workspace: '{}', projectId: '{}', threadIds: '[{}]'",
                event.workspaceId(), projectId, threadIds);

        return traceThreadService.processTraceThreads(threadIds, projectId);
    }

}
