package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.EntityProjectEvent;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.events.FeedbackScoresDeleted;
import com.comet.opik.api.events.GuardrailsCreated;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.events.TraceThreadsUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.api.AnnotationQueue.AnnotationScope.THREAD;
import static com.comet.opik.api.AnnotationQueue.AnnotationScope.TRACE;

/**
 * Decides whether a change to a trace or thread is worth routing work, and hands it off.
 *
 * <p>Automation filters can read anything the traces and threads pages filter on, and those values arrive
 * at different times: a trace's output and end time with its update, its spans and their usage and cost
 * separately, scores from an LLM judge seconds later or from a reviewer days later, guardrail results and
 * comments whenever they are written. So every event that can change what a filter sees re-submits the
 * trace or thread, and the consumer re-evaluates it against current state.
 *
 * <p>This class deliberately does almost nothing. It guards, then publishes. Loading configuration,
 * evaluating filters and writing queue items all happen in {@link AnnotationQueueRoutingSubscriber}, behind
 * the stream, where the work is durable, retried and concurrency-bounded. The event bus offers none of those,
 * and this feature has no backfill or manual re-run, so an event lost in flight would keep a trace out of a
 * review queue permanently and silently.
 *
 * <p>Admitted events go into the Redis buffer ({@link AnnotationQueueRoutingBufferService}), not straight
 * onto the stream. Repeated changes to one entity within the buffer window fold into one member there, and
 * the flush job publishes one message per (workspace, scope) batch, so the consumer evaluates each entity
 * once per window rather than once per event — and never reads state younger than the window, which keeps
 * it clear of ClickHouse replica lag.
 *
 * <p>The guard is the one read kept here, and it is cached: without it the stream would carry every
 * ingestion event in the deployment, the vast majority of which have no automation to satisfy. It checks
 * the event's project when there is one and falls back to the workspace when there is not; the project
 * scope proper is enforced in the consumer. Anything that costs a lookup to turn an event into entity ids —
 * a span's trace, a trace's thread — runs only after the guard has passed.
 */
@EagerSingleton
@Slf4j
public class AnnotationQueueRoutingListener {

    private final @NonNull AnnotationQueueAutomationService automationService;
    private final @NonNull AnnotationQueueRoutingBufferService bufferService;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull TraceThreadService traceThreadService;

    @Inject
    public AnnotationQueueRoutingListener(@NonNull AnnotationQueueAutomationService automationService,
            @NonNull AnnotationQueueRoutingBufferService bufferService,
            @NonNull FeatureFlags featureFlags,
            @NonNull SpanDAO spanDAO,
            @NonNull TraceDAO traceDAO,
            @NonNull TraceThreadService traceThreadService) {
        this.automationService = automationService;
        this.bufferService = bufferService;
        this.featureFlags = featureFlags;
        this.spanDAO = spanDAO;
        this.traceDAO = traceDAO;
        this.traceThreadService = traceThreadService;
    }

    @Subscribe
    public void onFeedbackScoresCreated(@NonNull FeedbackScoresCreated event) {
        onEntityEvent(event);
    }

    @Subscribe
    public void onFeedbackScoresDeleted(@NonNull FeedbackScoresDeleted event) {
        onEntityEvent(event);
    }

    @Subscribe
    public void onCommentsCreated(@NonNull CommentsCreated event) {
        if (event.entityType() != EntityType.SPAN) {
            onEntityEvent(event);
        }
    }

    @Subscribe
    public void onCommentsDeleted(@NonNull CommentsDeleted event) {
        if (event.entityType() != EntityType.SPAN) {
            onEntityEvent(event);
        }
    }

    @Subscribe
    public void onGuardrailsCreated(@NonNull GuardrailsCreated event) {
        onEntityEvent(event);
    }

    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        event.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId))
                .forEach((projectId, traces) -> {
                    buffer(event.workspaceId(), projectId, TRACE,
                            () -> Mono.just(traces.stream().map(Trace::id).collect(Collectors.toSet())));

                    Set<String> threadIds = traces.stream()
                            .map(Trace::threadId)
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toSet());
                    if (!threadIds.isEmpty()) {
                        // Read-only: ingestion creates the thread's model id, so one still missing is a
                        // thread this event cannot route yet, and its next trace routes it.
                        buffer(event.workspaceId(), projectId, THREAD,
                                () -> traceThreadService.findThreadModelIds(projectId, threadIds)
                                        .map(byThreadId -> Set.copyOf(byThreadId.values())));
                    }
                });
    }

    @Subscribe
    public void onTracesUpdated(@NonNull TracesUpdated event) {
        UUID projectId = singleProject(event.projectIds());
        buffer(event.workspaceId(), projectId, TRACE, () -> Mono.just(event.traceIds()));
        // A trace's input, output and times are its thread's first and last message, duration and times.
        buffer(event.workspaceId(), projectId, THREAD,
                () -> traceDAO.getThreadIdsByTraceIds(event.traceIds())
                        .flatMapMany(byProject -> Flux.fromIterable(byProject.entrySet()))
                        .flatMap(entry -> traceThreadService.findThreadModelIds(entry.getKey(), entry.getValue()))
                        .flatMapIterable(Map::values)
                        .collect(Collectors.toSet())
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                                .put(RequestContext.USER_NAME, event.userName())));
    }

    @Subscribe
    public void onSpansCreated(@NonNull SpansCreated event) {
        event.spans().stream()
                .collect(Collectors.groupingBy(Span::projectId,
                        Collectors.mapping(Span::traceId, Collectors.toSet())))
                .forEach((projectId, traceIds) -> buffer(event.workspaceId(), projectId, TRACE,
                        () -> Mono.just(traceIds)));
    }

    @Subscribe
    public void onSpansUpdated(@NonNull SpansUpdated event) {
        buffer(event.workspaceId(), null, TRACE, () -> Mono.just(event.traceIds()));
    }

    @Subscribe
    public void onSpansDeleted(@NonNull SpansDeleted event) {
        buffer(event.workspaceId(), event.projectId(), TRACE, () -> Mono.just(event.traceIds()));
    }

    @Subscribe
    public void onTraceThreadsCreated(@NonNull TraceThreadsCreated event) {
        buffer(event.workspaceId(), event.projectId(), THREAD,
                () -> Mono.just(event.traceThreadModels().stream()
                        .map(TraceThreadModel::id)
                        .collect(Collectors.toSet())));
    }

    @Subscribe
    public void onTraceThreadsUpdated(@NonNull TraceThreadsUpdated event) {
        buffer(event.workspaceId(), event.projectId(), THREAD, () -> Mono.just(event.threadModelIds()));
    }

    /**
     * Trace and thread events route their own entities; span events route the span's trace, since the
     * traces filters read span scores and span aggregates through the trace.
     */
    private void onEntityEvent(EntityProjectEvent event) {
        if (!featureFlags.isAnnotationQueueAutomationEnabled()) {
            return;
        }

        if (event.entityIds().isEmpty()) {
            log.warn("Received an event carrying no entity ids, type '{}', workspace '{}'",
                    event.getClass().getSimpleName(), event.workspaceId());
            return;
        }

        switch (event.entityType()) {
            case TRACE -> buffer(event.workspaceId(), event.projectId(), TRACE,
                    () -> Mono.just(event.entityIds()));
            case THREAD -> buffer(event.workspaceId(), event.projectId(), THREAD,
                    () -> Mono.just(event.entityIds()));
            // Queues hold traces, not spans: a span's new score can change whether its trace matches.
            case SPAN -> buffer(event.workspaceId(), event.projectId(), TRACE,
                    () -> spanDAO.getTraceIdsBySpanIds(event.entityIds())
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                                    .put(RequestContext.USER_NAME, event.userName())));
        }
    }

    /**
     * Resolves the entity ids only once the guard has passed, so a workspace with no automation pays for
     * nothing beyond the cached guard.
     */
    private void buffer(String workspaceId, @Nullable UUID projectId, AnnotationQueue.AnnotationScope scope,
            Supplier<Mono<Set<UUID>>> entityIds) {
        if (!featureFlags.isAnnotationQueueAutomationEnabled()) {
            return;
        }

        Mono.fromCallable(() -> automationService.hasEnabledAutomation(workspaceId, projectId, scope))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Boolean::booleanValue)
                .flatMap(__ -> entityIds.get())
                .flatMap(ids -> bufferService.add(workspaceId, scope, ids))
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Failed to buffer entities for annotation queue routing, "
                                + "scope '{}', workspace '{}'", scope, workspaceId, error));
    }

    private static UUID singleProject(Set<UUID> projectIds) {
        return projectIds.size() == 1 ? projectIds.iterator().next() : null;
    }
}
