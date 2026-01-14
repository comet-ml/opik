package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.DynamicAnnotationQueueService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Event listener that handles TracesCreated and TraceThreadsCreated events
 * to evaluate and add items to dynamic annotation queues.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DynamicAnnotationQueueListener {

    private final @NonNull DynamicAnnotationQueueService dynamicAnnotationQueueService;

    /**
     * Handles the TracesCreated event by evaluating traces against dynamic annotation queues.
     *
     * @param event the TracesCreated event containing the traces to evaluate
     */
    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        var tracesByProject = event.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId));

        var countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.info("DynamicAnnotationQueueListener: Received '{}' traces for workspace '{}': '{}'",
                event.traces().size(), event.workspaceId(), countMap);

        // Process each project's traces
        Flux.fromIterable(tracesByProject.entrySet())
                .flatMap(entry -> {
                    UUID projectId = entry.getKey();
                    var traces = entry.getValue();

                    log.debug("Evaluating '{}' traces for dynamic queues in project '{}'",
                            traces.size(), projectId);

                    return dynamicAnnotationQueueService.evaluateAndAddTraces(traces);
                })
                .doOnError(error -> log.error(
                        "Error processing traces for dynamic annotation queues in workspace '{}': {}",
                        event.workspaceId(), error.getMessage(), error))
                .doOnComplete(() -> log.debug(
                        "Completed dynamic annotation queue evaluation for workspace '{}'",
                        event.workspaceId()))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }

    /**
     * Handles the TraceThreadsCreated event by evaluating threads against dynamic annotation queues.
     *
     * @param event the TraceThreadsCreated event containing the threads to evaluate
     */
    @Subscribe
    public void onTraceThreadsCreated(@NonNull TraceThreadsCreated event) {
        var threads = event.traceThreadModels();

        if (threads == null || threads.isEmpty()) {
            return;
        }

        log.info("DynamicAnnotationQueueListener: Received '{}' threads for workspace '{}', project '{}'",
                threads.size(), event.workspaceId(), event.projectId());

        Flux.fromIterable(threads)
                .flatMap(dynamicAnnotationQueueService::evaluateAndAddThread)
                .doOnError(error -> log.error(
                        "Error processing threads for dynamic annotation queues in workspace '{}': {}",
                        event.workspaceId(), error.getMessage(), error))
                .doOnComplete(() -> log.debug(
                        "Completed dynamic annotation queue evaluation for threads in workspace '{}'",
                        event.workspaceId()))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }
}
