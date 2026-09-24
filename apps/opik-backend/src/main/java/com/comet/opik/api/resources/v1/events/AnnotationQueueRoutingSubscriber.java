package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueConditionEvaluator;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueRoutingMetrics;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.EntityFeedbackScores;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Does the routing work: loads automation config, reads the entities' effective scores, evaluates the
 * conditions and adds the matches to their queues.
 *
 * <p>All of this sits behind the stream rather than in the event listener because none of it is free — one
 * MySQL read, one ClickHouse read, and up to a write per matching queue. Behind the stream it is durable
 * (a replica dying leaves the message pending for {@code autoClaim}), retried, and concurrency-bounded by
 * the consumer group. In the listener it would be none of those, and there is no backfill or manual re-run
 * to recover a lost event.
 *
 * <p>One message at a time, and a message is already a batch: the buffer flush hands over one
 * (workspace, scope) group of everything scored in the buffer window, deduplicated, so a message is one
 * automation lookup and one score read however many entities it names. Nothing here looks across messages —
 * the stream always moves forward. Writes run as the system user, like every other background write; the
 * item's {@code source} records that automation added it.
 *
 * <p>Redelivery is safe: the message carries no decision, so the consumer re-evaluates from current state,
 * and {@code addItems} excludes anything the queue has held before. At-least-once therefore needs no
 * deduplication here.
 */
@Slf4j
@EagerSingleton
public class AnnotationQueueRoutingSubscriber extends BaseRedisSubscriber<AnnotationQueueRoutingMessage> {

    private static final String METRICS_NAMESPACE = "opik";
    private static final String METRICS_BASE_NAME = "annotation_queue_routing";

    private final AnnotationQueueRoutingConfig config;
    private final AnnotationQueueAutomationService automationService;
    private final AnnotationQueueConditionEvaluator evaluator;
    private final AnnotationQueueService annotationQueueService;
    private final FeedbackScoreDAO feedbackScoreDAO;
    private final TraceDAO traceDAO;
    private final TraceThreadDAO traceThreadDAO;

    @Inject
    public AnnotationQueueRoutingSubscriber(
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull AnnotationQueueAutomationService automationService,
            @NonNull AnnotationQueueConditionEvaluator evaluator,
            @NonNull AnnotationQueueService annotationQueueService,
            @NonNull FeedbackScoreDAO feedbackScoreDAO,
            @NonNull TraceDAO traceDAO,
            @NonNull TraceThreadDAO traceThreadDAO) {
        super(config, redisson, AnnotationQueueRoutingConfig.PAYLOAD_FIELD, METRICS_NAMESPACE, METRICS_BASE_NAME);
        this.config = config;
        this.automationService = automationService;
        this.evaluator = evaluator;
        this.annotationQueueService = annotationQueueService;
        this.feedbackScoreDAO = feedbackScoreDAO;
        this.traceDAO = traceDAO;
        this.traceThreadDAO = traceThreadDAO;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Annotation queue routing is disabled, skipping subscriber start");
            return;
        }
        log.info("Starting annotation queue routing subscriber: stream '{}', group '{}', batchSize '{}'",
                config.getStreamName(), config.getConsumerGroupName(), config.getConsumerBatchSize());
        super.start();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull AnnotationQueueRoutingMessage message) {
        return route(message)
                .doOnNext(routed -> {
                    if (routed > 0) {
                        log.info("Routed '{}' items into annotation queues, workspace '{}'",
                                routed, message.workspaceId());
                    }
                })
                .then();
    }

    /**
     * Scores are read first, then automations. That order is deliberate: automation scope is the
     * <em>project</em>, and the event does not name one — the batch score path cannot, because a batch may
     * span several projects. The scores come back carrying each entity's project, so reading them first is
     * what lets the automation lookup be narrowed to exactly the projects involved instead of sweeping the
     * whole workspace.
     */
    private Mono<Long> route(AnnotationQueueRoutingMessage message) {
        return readScores(message)
                .flatMap(scoresByEntity -> {
                    if (scoresByEntity.isEmpty()) {
                        return Mono.just(0L);
                    }

                    Set<UUID> projectIds = scoresByEntity.values().stream()
                            .map(EntityFeedbackScores::projectId)
                            .collect(Collectors.toSet());

                    return Mono
                            .fromCallable(() -> automationService.findEnabledByProjects(
                                    message.workspaceId(), projectIds, message.scope()))
                            .subscribeOn(Schedulers.boundedElastic())
                            // The listener guarded too, but config can change between publish and
                            // processing — an automation disabled in the meantime must not route.
                            .flatMap(automations -> automations.isEmpty()
                                    ? Mono.just(0L)
                                    : retainProductionEntities(scoresByEntity, projectIds, message)
                                            .flatMap(production -> production.isEmpty()
                                                    ? Mono.just(0L)
                                                    : addMatches(automations, production, message)));
                });
    }

    /**
     * Drops everything that was not logged by an SDK, the same gate online scoring applies via
     * {@link com.comet.opik.api.Source#isLoggingSource}: an automation routes production traffic to a human
     * reviewer, and playground, optimization and evaluator activity is a developer trying things or a run
     * scoring itself. Routing it would fill a review queue with work nobody asked to review.
     *
     * <p>Stricter than online scoring in one respect: that path also admits {@code EXPERIMENT}, because an
     * experiment's traces are what produce its metrics. Experiment traces are reviewed through the
     * experiment comparison view, not a queue, so they are excluded here.
     *
     * <p>It runs after the automation lookup so the read only happens for a project that actually has an
     * enabled automation, which is the minority of scored traffic.
     */
    private Mono<Map<UUID, EntityFeedbackScores>> retainProductionEntities(
            Map<UUID, EntityFeedbackScores> scoresByEntity, Set<UUID> projectIds,
            AnnotationQueueRoutingMessage message) {

        Set<UUID> entityIds = scoresByEntity.keySet();

        Mono<Set<UUID>> loggedBySdk = message.scope() == AnnotationQueue.AnnotationScope.THREAD
                ? traceThreadDAO.getLoggingSourceIds(projectIds, entityIds)
                : traceDAO.getLoggingSourceIds(projectIds, entityIds);

        return loggedBySdk
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER))
                .map(kept -> {
                    int skipped = entityIds.size() - kept.size();
                    if (skipped > 0) {
                        AnnotationQueueRoutingMetrics.NON_PRODUCTION_SKIPPED.add(skipped);
                        log.debug("Skipped '{}' of '{}' scored entities not logged by an SDK, workspace '{}'",
                                skipped, entityIds.size(), message.workspaceId());
                    }

                    return scoresByEntity.entrySet().stream()
                            .filter(entry -> kept.contains(entry.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                });
    }

    private Mono<Map<UUID, EntityFeedbackScores>> readScores(AnnotationQueueRoutingMessage message) {
        EntityType entityType = message.scope() == AnnotationQueue.AnnotationScope.THREAD
                ? EntityType.THREAD
                : EntityType.TRACE;

        // The scores carry the project id, which the message does not: on the batch score path it is absent
        // because one batch may span several projects. So one read answers both questions. Nothing here is
        // read younger than debounceDelay, which is what keeps the read clear of ClickHouse replica lag; an
        // entity with no scores visible is simply not routed until its next score.
        return feedbackScoreDAO.getEffectiveScores(entityType, message.entityIds())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private Mono<Long> addMatches(List<AnnotationQueueAutomationService.QueueAutomation> automations,
            Map<UUID, EntityFeedbackScores> scoresByEntity, AnnotationQueueRoutingMessage message) {

        // Group per queue so each queue takes one addItems call rather than one per entity. Items this
        // queue has held before are excluded inside addItems, which checks the history whenever the source
        // is AUTOMATED — that is what stops a reviewer's own score from re-adding what they just annotated.
        Map<UUID, Set<UUID>> matchesByQueue = automations.stream()
                .flatMap(automation -> scoresByEntity.values().stream()
                        .filter(entity -> automation.projectId().equals(entity.projectId()))
                        .filter(entity -> evaluator.matches(automation.conditions(), entity.scores()))
                        .map(entity -> Map.entry(automation.queueId(), entity.entityId())))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));

        if (matchesByQueue.isEmpty()) {
            return Mono.just(0L);
        }

        // Every queue is attempted even if an earlier one failed, so one bad queue cannot starve the rest.
        // But a failure is not swallowed: it is collected and rethrown once the others are done, which
        // leaves the message pending for autoClaim to retry. Retrying costs almost nothing and cannot
        // duplicate anything, because addItems excludes what a queue has already held - so acknowledging a
        // failed write would trade a cheap retry for a permanently unrouted trace, there being no backfill.
        var failures = new ConcurrentLinkedQueue<Throwable>();

        return Flux.fromIterable(matchesByQueue.entrySet())
                .concatMap(entry -> annotationQueueService
                        .addItems(entry.getKey(), entry.getValue(), AnnotationQueueItemSource.AUTOMATED)
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER))
                        // Nothing currently bounds how much a queue can accumulate, so this counter is the
                        // only way to see a badly scoped condition filling one up.
                        .doOnNext(added -> AnnotationQueueRoutingMetrics.ITEMS_ROUTED.add(added))
                        .onErrorResume(error -> {
                            log.error("Failed to route items into annotation queue '{}'", entry.getKey(), error);
                            AnnotationQueueRoutingMetrics.QUEUE_WRITE_FAILURES.add(1);
                            failures.add(error);
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum)
                .flatMap(routed -> {
                    if (failures.isEmpty()) {
                        return Mono.just(routed);
                    }
                    Throwable primary = failures.poll();
                    failures.forEach(primary::addSuppressed);
                    return Mono.error(primary);
                });
    }
}
