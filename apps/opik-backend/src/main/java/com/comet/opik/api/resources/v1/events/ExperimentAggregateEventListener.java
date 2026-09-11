package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.AssertionResultsCreated;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.CommentsUpdated;
import com.comet.opik.api.events.ExperimentItemsCreated;
import com.comet.opik.api.events.ExperimentItemsDeleted;
import com.comet.opik.api.events.ExperimentUpdated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.events.FeedbackScoresDeleted;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentItemRef;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentTraceRef;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class ExperimentAggregateEventListener {

    public static final Set<ExperimentStatus> FINISHED_STATUSES = Set.of(
            ExperimentStatus.COMPLETED, ExperimentStatus.CANCELLED);

    private final ExperimentItemService experimentItemService;
    private final ExperimentAggregationPublisher publisher;
    private final ExperimentAggregatesService experimentAggregatesService;
    private final ExperimentDenormalizationConfig config;

    @Inject
    public ExperimentAggregateEventListener(
            ExperimentItemService experimentItemService,
            ExperimentAggregationPublisher publisher,
            ExperimentAggregatesService experimentAggregatesService,
            @Config("experimentDenormalization") ExperimentDenormalizationConfig config) {
        this.experimentItemService = experimentItemService;
        this.publisher = publisher;
        this.experimentAggregatesService = experimentAggregatesService;
        this.config = config;
    }

    @Subscribe
    public void onExperimentUpdated(ExperimentUpdated event) {
        if (!config.isEnabled()) {
            log.debug("Ignoring '{}' event: experiment denormalization config is disabled",
                    ExperimentUpdated.class.getSimpleName());
            return;
        }
        if (FINISHED_STATUSES.contains(event.newStatus())) {
            publisher.publish(Set.of(event.experimentId()), event.workspaceId(), event.userName())
                    .subscribe(null,
                            e -> log.error(
                                    "Error triggering aggregation for experiment '{}' in workspace '{}'",
                                    event.experimentId(), event.workspaceId(), e));
        }
    }

    @Subscribe
    public void onExperimentItemsCreated(ExperimentItemsCreated event) {
        triggerByExperimentIds(event.experimentIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for experiment items created", e));
    }

    @Subscribe
    public void onExperimentItemsDeleted(@NonNull ExperimentItemsDeleted event) {
        Map<UUID, Set<UUID>> itemsByExperiment = event.itemRefs().stream()
                .collect(Collectors.groupingBy(
                        ExperimentItemRef::experimentId,
                        Collectors.mapping(ExperimentItemRef::itemId, Collectors.toSet())));

        Flux.fromIterable(itemsByExperiment.entrySet())
                .concatMap(entry -> experimentAggregatesService
                        .deleteItemAggregatesByItemIds(entry.getKey(), entry.getValue()))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .then(triggerByExperimentIds(event.experimentIds(), event.workspaceId(), event.userName()))
                .subscribe(null, e -> log.error("Error triggering aggregation for experiment items deleted", e));
    }

    @Subscribe
    public void onExperimentsDeleted(@NonNull ExperimentsDeleted event) {
        if (CollectionUtils.isEmpty(event.experimentIds())) {
            return;
        }
        experimentAggregatesService.deleteByExperimentIds(event.experimentIds())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe(null,
                        e -> log.error(
                                "Error deleting aggregated experiments, workspaceId '{}', size '{}'",
                                event.workspaceId(), event.experimentIds().size(), e));
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        // Split by project so each aggregation trigger targets a single project (enables project_id pruning).
        Map<UUID, Set<UUID>> traceIdsByProject = event.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId, Collectors.mapping(Trace::id, Collectors.toSet())));
        triggerByTraceIdsPerProject(traceIdsByProject, event.workspaceId(), event.userName(),
                "Error triggering aggregation for traces created");
    }

    @Subscribe
    public void onTracesUpdated(TracesUpdated event) {
        var errorMessage = "Error triggering aggregation for traces updated";
        var traceIdToProjectId = event.traceIdToProjectId();

        if (MapUtils.isEmpty(traceIdToProjectId)) {
            // No per-trace -> project mapping available: fall back to a single workspace-scoped trigger that
            // relies on the trace_id skip index, instead of re-scanning the full id set once per project.
            triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName(), null)
                    .subscribe(null, e -> log.error(errorMessage, e));
            return;
        }

        // Split the mapped trace ids by project so each aggregation trigger targets a single project.
        Map<UUID, Set<UUID>> traceIdsByProject = traceIdToProjectId.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
        triggerByTraceIdsPerProject(traceIdsByProject, event.workspaceId(), event.userName(), errorMessage);

        // The mapping may not cover every trace id in the event (e.g. getProjectIdsByTraceIds omitted some);
        // route the uncovered ones through the workspace-scoped fallback so their aggregation isn't skipped.
        Set<UUID> uncoveredTraceIds = event.traceIds().stream()
                .filter(traceId -> !traceIdToProjectId.containsKey(traceId))
                .collect(Collectors.toSet());
        if (!uncoveredTraceIds.isEmpty()) {
            triggerByTraceIds(uncoveredTraceIds, event.workspaceId(), event.userName(), null)
                    .subscribe(null, e -> log.error(errorMessage, e));
        }
    }

    @Subscribe
    public void onTracesDeleted(TracesDeleted event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName(), event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for traces deleted", e));
    }

    @Subscribe
    public void onSpansCreated(SpansCreated event) {
        Map<UUID, Set<UUID>> traceIdsByProject = event.spans().stream()
                .collect(Collectors.groupingBy(Span::projectId, Collectors.mapping(Span::traceId, Collectors.toSet())));
        triggerByTraceIdsPerProject(traceIdsByProject, event.workspaceId(), event.userName(),
                "Error triggering aggregation for spans created");
    }

    @Subscribe
    public void onSpansUpdated(SpansUpdated event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName(), null)
                .subscribe(null, e -> log.error("Error triggering aggregation for spans updated", e));
    }

    @Subscribe
    public void onSpansDeleted(SpansDeleted event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName(), event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for spans deleted", e));
    }

    private void triggerByTraceIdsPerProject(Map<UUID, Set<UUID>> traceIdsByProject, String workspaceId,
            String userName, String errorMessage) {
        // Each per-project trigger logs and recovers via onErrorResume, so one project's failure doesn't
        // abort the rest and no error reaches the terminal subscriber.
        Flux.fromIterable(traceIdsByProject.entrySet())
                .concatMap(entry -> triggerByTraceIds(entry.getValue(), workspaceId, userName, entry.getKey())
                        .onErrorResume(e -> logAndContinue(errorMessage, e)))
                .subscribe();
    }

    // Keeps the per-project fan-out going when one project's aggregation fails.
    private Mono<Void> logAndContinue(String errorMessage, Throwable e) {
        log.error(errorMessage, e);
        return Mono.empty();
    }

    @Subscribe
    public void onFeedbackScoresCreated(FeedbackScoresCreated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for feedback scores created", e));
    }

    @Subscribe
    public void onAssertionResultsCreated(AssertionResultsCreated event) {
        log.info("Received assertion results created event on workspaceId '{}', entityType '{}', entityIds size '{}'",
                event.workspaceId(), event.entityType(), event.entityIds().size());
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null,
                        e -> log.error(
                                "Error triggering aggregation for assertion results created on workspaceId '{}', entityType '{}', entityIds size '{}'",
                                event.workspaceId(), event.entityType(), event.entityIds().size(), e));
    }

    @Subscribe
    public void onFeedbackScoresDeleted(FeedbackScoresDeleted event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for feedback scores deleted", e));
    }

    @Subscribe
    public void onCommentsCreated(CommentsCreated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments created", e));
    }

    @Subscribe
    public void onCommentsUpdated(CommentsUpdated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments updated", e));
    }

    @Subscribe
    public void onCommentsDeleted(CommentsDeleted event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName(),
                event.projectId())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments deleted", e));
    }

    private Mono<Void> triggerByEntityIds(Set<UUID> entityIds, EntityType entityType, String workspaceId,
            String userName, @Nullable UUID projectId) {
        if (!config.isEnabled()) {
            log.debug(
                    "Ignoring entity aggregation trigger for entity type '{}': experiment denormalization config is disabled",
                    entityType);
            return Mono.empty();
        }
        if (CollectionUtils.isEmpty(entityIds)) {
            return Mono.empty();
        }
        return switch (entityType) {
            case TRACE -> triggerByTraceIds(entityIds, workspaceId, userName, projectId);
            case SPAN -> triggerBySpanIds(entityIds, workspaceId, userName, projectId);
            default -> {
                log.debug("Skipping aggregation trigger for entity type '{}'", entityType);
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> triggerByExperimentIds(Set<UUID> experimentIds, String workspaceId, String userName) {
        return triggerAggregation(experimentIds, workspaceId, userName,
                ids -> experimentItemService
                        .filterExperimentIdsByStatus(ids, FINISHED_STATUSES)
                        .collect(Collectors.toSet()));
    }

    private Mono<Void> triggerByTraceIds(Set<UUID> traceIds, String workspaceId, String userName,
            @Nullable UUID projectId) {
        return triggerAggregation(traceIds, workspaceId, userName,
                ids -> experimentItemService
                        .getExperimentRefsByTraceIds(ids, FINISHED_STATUSES, projectId)
                        .map(ExperimentTraceRef::experimentId)
                        .collect(Collectors.toSet()));
    }

    private Mono<Void> triggerBySpanIds(Set<UUID> spanIds, String workspaceId, String userName,
            @Nullable UUID projectId) {
        return triggerAggregation(spanIds, workspaceId, userName,
                ids -> experimentItemService
                        .getExperimentRefsBySpanIds(ids, FINISHED_STATUSES, projectId)
                        .map(ExperimentTraceRef::experimentId)
                        .collect(Collectors.toSet()));
    }

    private Mono<Void> triggerAggregation(Set<UUID> entityIds, String workspaceId, String userName,
            Function<Set<UUID>, Mono<Set<UUID>>> lookupExperimentIds) {
        if (!config.isEnabled()) {
            log.debug("Ignoring aggregation trigger: experiment denormalization config is disabled");
            return Mono.empty();
        }
        if (CollectionUtils.isEmpty(entityIds)) {
            return Mono.empty();
        }

        return lookupExperimentIds.apply(entityIds)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .flatMap(experimentIds -> publishIfNotEmpty(experimentIds, workspaceId, userName));
    }

    private Mono<Void> publishIfNotEmpty(Set<UUID> experimentIds, String workspaceId, String userName) {
        if (CollectionUtils.isEmpty(experimentIds)) {
            log.warn("No finished experiments to publish for workspace '{}'", workspaceId);
            return Mono.empty();
        }
        return publisher.publish(experimentIds, workspaceId, userName);
    }
}
