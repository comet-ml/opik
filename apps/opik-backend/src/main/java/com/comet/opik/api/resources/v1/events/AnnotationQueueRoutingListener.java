package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingPublisher;
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
 * <p>Publishing straight to the stream, with no buffer in front of it. An earlier revision debounced here
 * in Redis so that repeated scores on one entity collapsed before they were published; the stream already
 * is that buffer, and the collapsing belongs on the read side, where the consumer folds a batch by entity
 * before doing any of the expensive work. One event is therefore one XADD, and the event already carries a
 * whole batch of entity ids, so a bulk score call is one message rather than one per entity.
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
    private final @NonNull AnnotationQueueRoutingPublisher publisher;
    private final @NonNull AnnotationQueueRoutingConfig config;

    @Inject
    public AnnotationQueueRoutingListener(@NonNull AnnotationQueueAutomationService automationService,
            @NonNull AnnotationQueueRoutingPublisher publisher,
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config) {
        this.automationService = automationService;
        this.publisher = publisher;
        this.config = config;
    }

    @Subscribe
    public void onFeedbackScoresCreated(@NonNull FeedbackScoresCreated event) {
        var scope = scopeOf(event.entityType());

        // Spans are never annotation queue items, so they cannot route. The disabled check belongs here
        // rather than only in the buffer: the guard below is a database round trip on the busiest event in
        // the system, and a switched-off feature should not pay for it.
        if (!config.isEnabled() || scope == null || event.entityIds().isEmpty()) {
            return;
        }

        // The project-scoped guard when the event names a project; the workspace one otherwise, which the
        // batch score path needs because a batch may span several projects. The workspace variant is not
        // the scan its name suggests: automation_rules_workspace_action_enabled_idx covers
        // (workspace_id, action, enabled), so it seeks on an equality prefix and reads the id it needs off
        // the index. Both flavours are a lookup, not a walk.
        Mono.fromCallable(() -> event.projectId() != null
                ? automationService.hasEnabledAutomation(event.workspaceId(), event.projectId(), scope)
                : automationService.hasEnabledAutomation(event.workspaceId(), scope))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Boolean::booleanValue)
                .flatMap(__ -> publisher.enqueue(event.workspaceId(), event.userName(), scope, event.entityIds(),
                        event.getScoreNames()))
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Failed to publish entities for annotation queue routing, workspace '{}'",
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
