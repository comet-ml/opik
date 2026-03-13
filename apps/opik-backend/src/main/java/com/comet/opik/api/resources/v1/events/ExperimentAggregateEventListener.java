package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.CommentsUpdated;
import com.comet.opik.api.events.ExperimentItemsCreated;
import com.comet.opik.api.events.ExperimentItemsDeleted;
import com.comet.opik.api.events.ExperimentUpdated;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.events.FeedbackScoresDeleted;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentTraceRef;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class ExperimentAggregateEventListener {

    private static final Set<ExperimentStatus> FINISHED_STATUSES = Set.of(
            ExperimentStatus.COMPLETED, ExperimentStatus.CANCELLED);

    private final ExperimentItemService experimentItemService;
    private final ExperimentAggregationPublisher publisher;
    private final ExperimentDenormalizationConfig config;

    @Inject
    public ExperimentAggregateEventListener(
            ExperimentItemService experimentItemService,
            ExperimentAggregationPublisher publisher,
            @Config("experimentDenormalization") ExperimentDenormalizationConfig config) {
        this.experimentItemService = experimentItemService;
        this.publisher = publisher;
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
    public void onExperimentItemsDeleted(ExperimentItemsDeleted event) {
        triggerByExperimentIds(event.experimentIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for experiment items deleted", e));
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        var traceIds = event.traces().stream().map(Trace::id).collect(Collectors.toSet());
        triggerByTraceIds(traceIds, event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for traces created", e));
    }

    @Subscribe
    public void onTracesUpdated(TracesUpdated event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for traces updated", e));
    }

    @Subscribe
    public void onTracesDeleted(TracesDeleted event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for traces deleted", e));
    }

    @Subscribe
    public void onSpansCreated(SpansCreated event) {
        var traceIds = event.spans().stream().map(Span::traceId).collect(Collectors.toSet());
        triggerByTraceIds(traceIds, event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for spans created", e));
    }

    @Subscribe
    public void onSpansUpdated(SpansUpdated event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for spans updated", e));
    }

    @Subscribe
    public void onSpansDeleted(SpansDeleted event) {
        triggerByTraceIds(event.traceIds(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for spans deleted", e));
    }

    @Subscribe
    public void onFeedbackScoresCreated(FeedbackScoresCreated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for feedback scores created", e));
    }

    @Subscribe
    public void onFeedbackScoresDeleted(FeedbackScoresDeleted event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for feedback scores deleted", e));
    }

    @Subscribe
    public void onCommentsCreated(CommentsCreated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments created", e));
    }

    @Subscribe
    public void onCommentsUpdated(CommentsUpdated event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments updated", e));
    }

    @Subscribe
    public void onCommentsDeleted(CommentsDeleted event) {
        triggerByEntityIds(event.entityIds(), event.entityType(), event.workspaceId(), event.userName())
                .subscribe(null, e -> log.error("Error triggering aggregation for comments deleted", e));
    }

    private Mono<Void> triggerByEntityIds(Set<UUID> entityIds, EntityType entityType, String workspaceId,
            String userName) {
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
            case TRACE -> triggerByTraceIds(entityIds, workspaceId, userName);
            case SPAN -> triggerBySpanIds(entityIds, workspaceId, userName);
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

    private Mono<Void> triggerByTraceIds(Set<UUID> traceIds, String workspaceId, String userName) {
        return triggerAggregation(traceIds, workspaceId, userName,
                ids -> experimentItemService
                        .getExperimentRefsByTraceIds(ids, FINISHED_STATUSES)
                        .map(ExperimentTraceRef::experimentId)
                        .collect(Collectors.toSet()));
    }

    private Mono<Void> triggerBySpanIds(Set<UUID> spanIds, String workspaceId, String userName) {
        return triggerAggregation(spanIds, workspaceId, userName,
                ids -> experimentItemService
                        .getExperimentRefsBySpanIds(ids, FINISHED_STATUSES)
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
