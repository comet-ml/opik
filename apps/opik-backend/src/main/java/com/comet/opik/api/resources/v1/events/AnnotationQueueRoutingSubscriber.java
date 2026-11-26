package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueRoutingMetrics;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.ThreadDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Does the routing work: loads automation config, runs each automation's filters over the entities in the
 * message and adds the matches to their queues.
 *
 * <p>All of this sits behind the stream rather than in the event listener because none of it is free — one
 * MySQL read, a ClickHouse read per automation, and up to a write per matching queue. Behind the stream it is
 * durable (a replica dying leaves the message pending for {@code autoClaim}), retried, and
 * concurrency-bounded by the consumer group. In the listener it would be none of those, and there is no
 * backfill or manual re-run to recover a lost event.
 *
 * <p>One message at a time, and a message is already a batch: the buffer flush hands over one
 * (workspace, scope) group of everything that changed in the buffer window, deduplicated. Nothing here looks
 * across messages — the stream always moves forward. Writes run as the system user, like every other
 * background write; the item's {@code source} records that automation added it.
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

    // Only ids come back from the match queries, so the heavy text columns are never read.
    private static final Set<Trace.TraceField> EXCLUDED_TRACE_FIELDS = Set.of(
            Trace.TraceField.INPUT, Trace.TraceField.OUTPUT, Trace.TraceField.METADATA);

    private final AnnotationQueueRoutingConfig config;
    private final FeatureFlags featureFlags;
    private final AnnotationQueueAutomationService automationService;
    private final AnnotationQueueService annotationQueueService;
    private final TraceDAO traceDAO;
    private final ThreadDAO threadDAO;
    private final TraceThreadDAO traceThreadDAO;
    private final TraceThreadService traceThreadService;

    @Inject
    public AnnotationQueueRoutingSubscriber(
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeatureFlags featureFlags,
            @NonNull AnnotationQueueAutomationService automationService,
            @NonNull AnnotationQueueService annotationQueueService,
            @NonNull TraceDAO traceDAO,
            @NonNull ThreadDAO threadDAO,
            @NonNull TraceThreadDAO traceThreadDAO,
            @NonNull TraceThreadService traceThreadService) {
        super(config, redisson, AnnotationQueueRoutingConfig.PAYLOAD_FIELD, METRICS_NAMESPACE, METRICS_BASE_NAME);
        this.config = config;
        this.featureFlags = featureFlags;
        this.automationService = automationService;
        this.annotationQueueService = annotationQueueService;
        this.traceDAO = traceDAO;
        this.threadDAO = threadDAO;
        this.traceThreadDAO = traceThreadDAO;
        this.traceThreadService = traceThreadService;
    }

    @Override
    public void start() {
        if (!featureFlags.isAnnotationQueueAutomationEnabled()) {
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
                        log.info("Routed items into annotation queues, count '{}', workspace '{}'",
                                routed, message.workspaceId());
                    }
                })
                .then();
    }

    /**
     * An entity named by the message, with what routing needs to know about it. {@code threadId} is the
     * thread's own id, which the threads filters address it by; it is null for traces.
     */
    @Builder(toBuilder = true)
    private record RoutedEntity(@NonNull UUID id, @NonNull UUID projectId, String threadId) {
    }

    /**
     * Entities are resolved to their projects first, then automations are looked up. Automation scope is the
     * <em>project</em>, and the message does not name one — several of the events feeding it cannot, because
     * one batch may span several projects. Resolving first is what lets the automation lookup be narrowed to
     * exactly the projects involved instead of sweeping the whole workspace.
     */
    private Mono<Long> route(AnnotationQueueRoutingMessage message) {
        return readEntities(message)
                .flatMap(entities -> {
                    if (entities.isEmpty()) {
                        return Mono.just(0L);
                    }

                    Set<UUID> projectIds = entities.stream()
                            .map(RoutedEntity::projectId)
                            .collect(Collectors.toSet());

                    return Mono
                            .fromCallable(() -> automationService.findEnabledByProjects(
                                    message.workspaceId(), projectIds, message.scope()))
                            .subscribeOn(Schedulers.boundedElastic())
                            // The listener guarded too, but config can change between publish and
                            // processing — an automation disabled in the meantime must not route.
                            .flatMap(automations -> automations.isEmpty()
                                    ? Mono.just(0L)
                                    : retainProductionEntities(entities, projectIds, message)
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
     * enabled automation, which is the minority of traffic.
     */
    private Mono<List<RoutedEntity>> retainProductionEntities(List<RoutedEntity> entities,
            Set<UUID> projectIds, AnnotationQueueRoutingMessage message) {

        Set<UUID> entityIds = entities.stream().map(RoutedEntity::id).collect(Collectors.toSet());

        Mono<Set<UUID>> loggedBySdk = message.scope() == AnnotationQueue.AnnotationScope.THREAD
                ? traceThreadDAO.getLoggingSourceIds(projectIds, entityIds)
                : traceDAO.getLoggingSourceIds(projectIds, entityIds);

        return withSystemContext(loggedBySdk, message)
                .map(kept -> {
                    var production = entities.stream()
                            .filter(entity -> kept.contains(entity.id()))
                            .toList();
                    int skipped = entities.size() - production.size();
                    if (skipped > 0) {
                        AnnotationQueueRoutingMetrics.NON_PRODUCTION_SKIPPED.add(skipped);
                        log.debug(
                                "Skipped entities not logged by an SDK, skipped '{}', received '{}', workspace '{}'",
                                skipped, entities.size(), message.workspaceId());
                    }

                    return production;
                });
    }

    /**
     * An entity that cannot be resolved — deleted since, or not yet visible on this replica — is dropped:
     * there is nothing to route, and its next change publishes it again. A trace id reused across projects yields
     * one entity per project, so each project's automations evaluate its own trace.
     */
    private Mono<List<RoutedEntity>> readEntities(AnnotationQueueRoutingMessage message) {
        List<UUID> ids = List.copyOf(message.entityIds());

        if (message.scope() == AnnotationQueue.AnnotationScope.THREAD) {
            return traceThreadService.getThreadIdModelsByThreadModelIds(ids)
                    .map(models -> models.stream()
                            .map(model -> RoutedEntity.builder()
                                    .id(model.id())
                                    .projectId(model.projectId())
                                    .threadId(model.threadId())
                                    .build())
                            .distinct()
                            .toList());
        }

        return withSystemContext(traceDAO.getAllProjectIdsByTraceIdsBounded(Set.copyOf(ids)), message)
                .map(projectIdsByTrace -> projectIdsByTrace.entrySet().stream()
                        .flatMap(entry -> entry.getValue().stream().map(projectId -> RoutedEntity.builder()
                                .id(entry.getKey())
                                .projectId(projectId)
                                .build()))
                        .toList());
    }

    /**
     * A search to run: one filter group over the batch's entities of one project.
     */
    private record GroupSearch(UUID projectId, List<Filter> filters) {
    }

    /**
     * Runs each distinct (project, filter group) search once, then adds every automation's matches to its queue.
     *
     * <p>Automations of one project often share filter groups, and each search is a ClickHouse query, so a group is
     * searched once however many automations use it. Searches run {@code searchConcurrency} at a time; queue
     * writes stay sequential. Each search is the traces or threads search the UI runs, narrowed to the candidates
     * by id, so a filter here selects exactly what it selects on the traces or threads page.
     */
    private Mono<Long> addMatches(List<AnnotationQueueAutomationService.QueueAutomation> automations,
            List<RoutedEntity> entities, AnnotationQueueRoutingMessage message) {

        var failures = new ConcurrentLinkedQueue<Throwable>();

        Map<UUID, List<RoutedEntity>> candidatesByProject = entities.stream()
                .collect(Collectors.groupingBy(RoutedEntity::projectId));

        Set<GroupSearch> searches = automations.stream()
                .filter(automation -> candidatesByProject.containsKey(automation.projectId()))
                .flatMap(automation -> automation.filterGroups().stream()
                        .map(filters -> new GroupSearch(automation.projectId(), filters)))
                .collect(Collectors.toSet());

        return Flux.fromIterable(searches)
                .flatMap(groupSearch -> withSystemContext(search(message.scope(), groupSearch.projectId(),
                        groupSearch.filters(), candidatesByProject.get(groupSearch.projectId())), message)
                        .collect(Collectors.toSet())
                        .map(matches -> Map.entry(groupSearch, matches))
                        .onErrorResume(error -> {
                            log.debug("Failed to search for annotation queue automation matches, projectId '{}'",
                                    groupSearch.projectId(), error);
                            AnnotationQueueRoutingMetrics.SEARCH_FAILURES.add(1);
                            failures.add(error);
                            return Mono.empty();
                        }), config.getSearchConcurrency())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMapMany(matchesBySearch -> Flux.fromIterable(automations)
                        .concatMap(automation -> addToQueue(automation, matchesBySearch, message, failures)))
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

    /**
     * Adds what any of the automation's filter groups matched.
     */
    private Mono<Long> addToQueue(AnnotationQueueAutomationService.QueueAutomation automation,
            Map<GroupSearch, Set<UUID>> matchesBySearch, AnnotationQueueRoutingMessage message,
            Collection<Throwable> failures) {

        Set<UUID> matches = automation.filterGroups().stream()
                .map(filters -> matchesBySearch.getOrDefault(new GroupSearch(automation.projectId(), filters),
                        Set.of()))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        if (matches.isEmpty()) {
            return Mono.just(0L);
        }

        return withSystemContext(annotationQueueService.addItems(automation.queueId(), matches,
                AnnotationQueueItemSource.AUTOMATED), message)
                .doOnNext(AnnotationQueueRoutingMetrics.ITEMS_ROUTED::add)
                .onErrorResume(error -> {
                    log.debug("Failed to route items into annotation queue, queueId '{}'", automation.queueId(),
                            error);
                    AnnotationQueueRoutingMetrics.QUEUE_WRITE_FAILURES.add(1);
                    failures.add(error);
                    return Mono.just(0L);
                });
    }

    private Flux<UUID> search(AnnotationQueue.AnnotationScope scope, UUID projectId, List<? extends Filter> filters,
            List<RoutedEntity> candidates) {

        if (scope == AnnotationQueue.AnnotationScope.THREAD) {
            return threadDAO.search(candidates.size(), TraceSearchCriteria.builder()
                    .projectId(projectId)
                    .filters(filters)
                    .threadIds(candidates.stream().map(RoutedEntity::threadId).collect(Collectors.toSet()))
                    .build())
                    // A thread whose row is not written yet has no model id; writing it routes the thread again.
                    .mapNotNull(TraceThread::threadModelId);
        }

        return traceDAO.search(candidates.size(), TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(filters)
                .traceIds(candidates.stream().map(RoutedEntity::id).collect(Collectors.toSet()))
                .exclude(EXCLUDED_TRACE_FIELDS)
                .build())
                .map(Trace::id);
    }

    private static <T> Mono<T> withSystemContext(Mono<T> mono, AnnotationQueueRoutingMessage message) {
        return mono.contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private static <T> Flux<T> withSystemContext(Flux<T> flux, AnnotationQueueRoutingMessage message) {
        return flux.contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }
}
