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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    public void stop() {
        if (!config.isEnabled()) {
            return;
        }
        log.info("Stopping annotation queue routing subscriber");
        super.stop();
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
        return loadScores(message)
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
     * <p>Deliberately a separate read rather than a predicate folded into the score query. Filtered-out
     * entities would otherwise be indistinguishable from entities whose scores had not landed yet, and
     * {@code loadScores} would spend a delayed re-read on each one and then count it as unresolved.
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
                        .put(RequestContext.USER_NAME, message.userName()))
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

    /**
     * Reads the entities' effective scores, with one cheap guard against reading before the write is
     * visible.
     *
     * <p>The event names the entities whose scores just changed, so finding <em>no</em> scores at all for
     * one of them means the read saw less than the write produced. Two causes: ClickHouse replication lag
     * on a multi-node cluster (the write landed on another replica), or a score whose
     * {@code scoreDestination} sent it to the assertion-results table, which never reaches
     * {@code feedback_scores} at all.
     *
     * <p>The guard is a single delayed re-read of only the entities that came back empty. It costs nothing
     * in the normal case, and it matters because of what the alternative loses: a stale read makes the
     * conditions not match, and if that was the last score the trace will ever receive, nothing
     * re-triggers. With no backfill the trace would then never be routed — silently and permanently.
     */
    private Mono<Map<UUID, EntityFeedbackScores>> loadScores(AnnotationQueueRoutingMessage message) {
        return readScores(message, message.entityIds())
                .flatMap(scores -> {
                    Set<UUID> missing = missingEntities(message.entityIds(), scores);
                    if (missing.isEmpty()) {
                        return Mono.just(scores);
                    }

                    AnnotationQueueRoutingMetrics.STALE_READS.add(1);
                    log.debug("No scores found for '{}' of '{}' entities named by the event, re-reading after '{}'",
                            missing.size(), message.entityIds().size(), config.getStaleReadRetryDelay());

                    return Mono.delay(config.getStaleReadRetryDelay().toJavaDuration())
                            .then(readScores(message, missing))
                            .map(retried -> {
                                if (retried.isEmpty()) {
                                    return scores;
                                }
                                var merged = new HashMap<>(scores);
                                merged.putAll(retried);
                                return Map.copyOf(merged);
                            })
                            .doOnNext(merged -> {
                                int unresolved = missingEntities(message.entityIds(), merged).size();
                                if (unresolved > 0) {
                                    AnnotationQueueRoutingMetrics.UNRESOLVED_ENTITIES.add(unresolved);
                                }
                            });
                });
    }

    private Mono<Map<UUID, EntityFeedbackScores>> readScores(AnnotationQueueRoutingMessage message,
            Set<UUID> entityIds) {

        EntityType entityType = message.scope() == AnnotationQueue.AnnotationScope.THREAD
                ? EntityType.THREAD
                : EntityType.TRACE;

        // The scores carry the project id, which the event does not: on the batch score path it is absent
        // because one batch may span several projects. So one read answers both questions.
        return feedbackScoreDAO.getEffectiveScores(entityType, entityIds)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
    }

    private Set<UUID> missingEntities(Set<UUID> named, Map<UUID, EntityFeedbackScores> found) {
        return named.stream()
                .filter(entityId -> !found.containsKey(entityId))
                .collect(Collectors.toSet());
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

        return Flux.fromIterable(matchesByQueue.entrySet())
                // Per-queue isolation: one queue failing must not stop the others. The failure is swallowed
                // rather than rethrown so a single bad queue does not force the whole message to be retried,
                // re-doing the work for queues that already succeeded.
                .concatMap(entry -> annotationQueueService
                        .addItems(entry.getKey(), entry.getValue(), AnnotationQueueItemSource.AUTOMATED)
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                        // Nothing currently bounds how much a queue can accumulate, so this counter is the
                        // only way to see a badly scoped condition filling one up.
                        .doOnNext(added -> AnnotationQueueRoutingMetrics.ITEMS_ROUTED.add(added))
                        .onErrorResume(error -> {
                            log.error("Failed to route items into annotation queue '{}'", entry.getKey(), error);
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }
}
