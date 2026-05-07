package com.comet.opik.domain;

import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.RecentActivity.ActivityType;
import com.comet.opik.api.RecentActivity.RecentActivityItem;
import com.comet.opik.api.RecentActivity.RecentActivityPage;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Aggregates recent activity across multiple entity types (experiments, optimizations,
 * datasets, test suites, agent configs, alert events) for a project.
 * <p>
 * Each source is queried for its N most recent items (page 1 only, sorted by id DESC).
 * Results are merged, sorted by createdAt descending, and paginated in memory.
 * <p>
 * <b>Graceful degradation:</b> individual source failures are caught and logged, returning
 * partial results from the remaining sources rather than failing the entire request.
 * <p>
 * <b>Limitation:</b> only page 1 is reliable. Cross-source pagination beyond page 1
 * would require fetching page*size per source to guarantee correct ordering across types.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class RecentActivityService {

    private static final int LOOKBACK_DAYS = 30;

    private final @NonNull ExperimentService experimentService;
    private final @NonNull OptimizationService optimizationService;
    private final @NonNull AlertEventLogsDAO alertEventLogsDAO;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;
    private final @NonNull Provider<RequestContext> requestContext;

    public Mono<RecentActivityPage> getRecentActivity(@NonNull UUID projectId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        var experimentsMono = fetchExperiments(projectId, size);
        var optimizationsMono = fetchOptimizations(projectId, size);
        var alertsMono = fetchAlertEvents(projectId, size);
        var datasetsMono = fetchDatasetSources(workspaceId, projectId, size);
        var agentConfigsMono = fetchAgentConfigs(workspaceId, projectId, size);

        return Mono.zip(experimentsMono, optimizationsMono, alertsMono, datasetsMono, agentConfigsMono)
                .map(tuple -> {
                    var all = new ArrayList<RecentActivityItem>();
                    all.addAll(tuple.getT1());
                    all.addAll(tuple.getT2());
                    all.addAll(tuple.getT3());
                    all.addAll(tuple.getT4());
                    all.addAll(tuple.getT5());

                    all.sort(Comparator.comparing(RecentActivityItem::createdAt).reversed());

                    int offset = (page - 1) * size;
                    var content = all.stream().skip(offset).limit(size).toList();

                    return RecentActivityPage.builder()
                            .page(page)
                            .size(size)
                            .total(all.size())
                            .content(content)
                            .build();
                });
    }

    private Mono<List<RecentActivityItem>> fetchExperiments(UUID projectId, int size) {
        var criteria = ExperimentSearchCriteria.builder()
                .entityType(EntityType.TRACE)
                .projectId(projectId)
                .sortingFields(List.of())
                .types(Set.of(ExperimentType.REGULAR))
                .build();

        return experimentService.find(1, size, criteria)
                .map(page -> page.content().stream()
                        .map(e -> RecentActivityItem.builder()
                                .type(ActivityType.EXPERIMENT).id(e.id()).name(e.name())
                                .createdAt(e.createdAt()).resourceId(e.datasetId())
                                .createdBy(e.createdBy()).build())
                        .toList())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent experiments for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchOptimizations(UUID projectId, int size) {
        var criteria = OptimizationSearchCriteria.builder()
                .entityType(EntityType.TRACE)
                .projectId(projectId)
                .build();

        return optimizationService.find(1, size, criteria)
                .map(page -> page.content().stream()
                        .map(o -> RecentActivityItem.builder()
                                .type(ActivityType.OPTIMIZATION).id(o.id()).name(o.datasetName())
                                .createdAt(o.createdAt()).createdBy(o.createdBy()).build())
                        .toList())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent optimizations for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchAlertEvents(UUID projectId, int size) {
        var criteria = LogCriteria.builder()
                .size(size)
                .page(1)
                .markers(Map.of(UserLog.PROJECT_ID, projectId.toString()))
                .build();

        return alertEventLogsDAO.findLogs(criteria)
                .filter(logItem -> logItem.markers() != null
                        && logItem.markers().containsKey(UserLog.ALERT_ID))
                .map(logItem -> RecentActivityItem.builder()
                        .type(ActivityType.ALERT_EVENT)
                        .id(UUID.fromString(logItem.markers().get(UserLog.ALERT_ID)))
                        .name(logItem.message()).createdAt(logItem.timestamp()).build())
                .collectList()
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent alert events for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchDatasetSources(String workspaceId, UUID projectId, int size) {
        var minId = instantToUUIDMapper.toLowerBound(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS));
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findRecentActivityByProjectId(workspaceId, projectId, minId, size).stream()
                    .map(r -> RecentActivityItem.builder()
                            .type("evaluation_suite".equals(r.datasetType())
                                    ? ActivityType.TEST_SUITE_VERSION
                                    : ActivityType.DATASET_VERSION)
                            .id(r.datasetId()).name(r.datasetName())
                            .createdAt(r.createdAt()).createdBy(r.createdBy()).build())
                    .toList();
        })).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent dataset/test suite activity for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchAgentConfigs(String workspaceId, UUID projectId, int size) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AgentConfigDAO.class);
            return dao.getBlueprintHistory(workspaceId, projectId, size, 0).stream()
                    .map(bp -> RecentActivityItem.builder()
                            .type(ActivityType.AGENT_CONFIG_VERSION).id(bp.id()).name(bp.name())
                            .createdAt(bp.createdAt()).createdBy(bp.createdBy()).build())
                    .toList();
        })).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent agent configs for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }
}
