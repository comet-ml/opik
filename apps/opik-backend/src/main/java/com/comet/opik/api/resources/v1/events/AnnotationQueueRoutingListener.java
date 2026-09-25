package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Decides whether a feedback-score event is worth routing work, and hands it off (OPIK-6303).
 *
 * <p>Score-driven rather than trace-driven, because the conditions are score thresholds: a trace has no
 * scores when it is created, so trace creation could never satisfy one. Eligibility changes when a score
 * lands — for an LLM judge seconds after the trace, for a human reviewer possibly days.
 *
 * <p>This class deliberately does almost nothing. It guards, then publishes. Loading configuration,
 * reading scores, evaluating conditions and writing queue items all happen in
 * {@link AnnotationQueueRoutingSubscriber}, behind the stream, where the work is durable, retried and
 * concurrency-bounded. The event bus offers none of those, and this feature has no backfill or manual
 * re-run, so an event lost in flight would keep a trace out of a review queue permanently and silently.
 *
 * <p>Admitted events go into the Redis buffer ({@link AnnotationQueueRoutingBufferService}), not straight
 * onto the stream. Repeated scores on one entity within the buffer window fold into one member there, and
 * the flush job publishes one message per (workspace, scope) batch, so the consumer evaluates each entity
 * once per window rather than once per score event — and never reads scores younger than the
 * window, which keeps it clear of ClickHouse replica lag.
 *
 * <p>The one read kept here is the guard, and it earns its place: without it the stream would carry every
 * score event in the deployment, the vast majority of which have no automation to satisfy.
 * {@code OnlineScoringSampler} does the same thing — an uncached per-event rule lookup on the busiest
 * event in the system — so this is the established cost. It checks the event's project when there is one,
 * and falls back to the workspace when there is not; the project scope proper is enforced in the consumer,
 * which learns each entity's project from its scores.
 */
@EagerSingleton
@Slf4j
public class AnnotationQueueRoutingListener {

    private final @NonNull AnnotationQueueAutomationService automationService;
    private final @NonNull AnnotationQueueRoutingBufferService bufferService;
    private final @NonNull AnnotationQueueRoutingConfig config;

    @Inject
    public AnnotationQueueRoutingListener(@NonNull AnnotationQueueAutomationService automationService,
            @NonNull AnnotationQueueRoutingBufferService bufferService,
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config) {
        this.automationService = automationService;
        this.bufferService = bufferService;
        this.config = config;
    }

    @Subscribe
    public void onFeedbackScoresCreated(@NonNull FeedbackScoresCreated event) {
        // First, and silently: this fires for every score event in the deployment, so a switched-off
        // feature must not pay for the lookup below nor log a line each time.
        if (!config.isEnabled()) {
            return;
        }

        var scope = scopeOf(event.entityType());
        if (scope == null) {
            // Routine rather than anomalous: spans are scored like anything else, they just can never be
            // annotation queue items, so there is nothing to route them to.
            log.debug("Ignoring feedback scores for entity type '{}', which cannot be an annotation queue "
                    + "item, workspace '{}'", event.entityType(), event.workspaceId());
            return;
        }

        if (event.entityIds().isEmpty()) {
            // Every emitter checks this before posting, so arriving here means one of them stopped.
            log.warn("Received a feedback score event carrying no entity ids, scope '{}', workspace '{}'",
                    scope, event.workspaceId());
            return;
        }

        // The project id is absent on the batch score path, because one batch may span several projects;
        // the service widens the question to the workspace when it is. Either way it is an index seek on
        // automation_rules_workspace_action_enabled_idx, a lookup rather than a walk.
        Mono.fromCallable(() -> automationService.hasEnabledAutomation(event.workspaceId(),
                event.projectId(), scope))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Boolean::booleanValue)
                .flatMap(__ -> bufferService.add(event.workspaceId(), scope, event.entityIds()))
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Failed to buffer entities for annotation queue routing, workspace '{}'",
                                event.workspaceId(), error));
    }

    private AnnotationQueue.AnnotationScope scopeOf(EntityType entityType) {
        return switch (entityType) {
            case TRACE -> AnnotationQueue.AnnotationScope.TRACE;
            case THREAD -> AnnotationQueue.AnnotationScope.THREAD;
            default -> null;
        };
    }
}
