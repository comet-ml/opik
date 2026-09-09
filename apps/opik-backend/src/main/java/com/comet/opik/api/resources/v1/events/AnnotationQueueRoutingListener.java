package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

/**
 * Decides whether a feedback-score event is worth routing work, and hands it off (OPIK-6303).
 *
 * <p>Score-driven rather than trace-driven, because the conditions are score thresholds: a trace has no
 * scores when it is created, so trace creation could never satisfy one. Eligibility changes when a score
 * lands — for an LLM judge seconds after the trace, for a human reviewer possibly days.
 *
 * <p>This class deliberately does almost nothing. It guards, then marks the entity as pending. Loading
 * configuration, reading scores, evaluating conditions and writing queue items all happen in
 * {@link AnnotationQueueRoutingSubscriber}, behind the stream, where the work is durable, retried and
 * concurrency-bounded. The event bus offers none of those, and this feature has no backfill or manual
 * re-run, so an event lost in flight would keep a trace out of a review queue permanently and silently.
 *
 * <p>Recording rather than publishing is what makes ten scores on one trace one evaluation instead of
 * ten: {@link AnnotationQueueRoutingBufferService} collapses by entity, and a flush job drains each entity
 * once its debounce window has elapsed.
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
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AnnotationQueueRoutingListener {

    private final @NonNull AnnotationQueueAutomationService automationService;
    private final @NonNull AnnotationQueueRoutingBufferService bufferService;

    @Subscribe
    public void onFeedbackScoresCreated(@NonNull FeedbackScoresCreated event) {
        var scope = scopeOf(event.entityType());

        // Spans are never annotation queue items, so they cannot route.
        if (scope == null || event.entityIds().isEmpty()) {
            return;
        }

        Mono.fromCallable(
                () -> automationService.hasEnabledAutomation(event.workspaceId(), event.projectId(), scope))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Boolean::booleanValue)
                .flatMap(__ -> bufferService.record(event.workspaceId(), event.userName(), scope, event.entityIds(),
                        event.getScoreNames()))
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Failed to record entities for annotation queue routing, workspace '{}'",
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
