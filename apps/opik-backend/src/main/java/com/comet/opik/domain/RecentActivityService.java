package com.comet.opik.domain;

import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.RecentActivity;
import com.comet.opik.api.RecentActivity.ActivityType;
import com.comet.opik.api.RecentActivity.RecentActivityItem;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class RecentActivityService {

    private static final int SOURCE_QUERY_LIMIT = 10;

    private final @NonNull ExperimentService experimentService;
    private final @NonNull OptimizationService optimizationService;
    private final @NonNull AlertEventLogsDAO alertEventLogsDAO;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull Provider<RequestContext> requestContext;

    public Mono<RecentActivity> getRecentActivity(@NonNull UUID projectId, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        var experimentsMono = fetchExperiments(projectId);
        var optimizationsMono = fetchOptimizations(projectId);
        var alertsMono = fetchAlertEvents(projectId);
        var jdbiMono = fetchJdbiSources(workspaceId, projectId);

        return Mono.zip(experimentsMono, optimizationsMono, alertsMono, jdbiMono)
                .map(tuple -> {
                    var all = new ArrayList<RecentActivityItem>();
                    all.addAll(tuple.getT1());
                    all.addAll(tuple.getT2());
                    all.addAll(tuple.getT3());
                    all.addAll(tuple.getT4());

                    all.sort(Comparator.comparing(RecentActivityItem::createdAt).reversed());

                    return new RecentActivity(all.stream().limit(size).toList());
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch recent activity for project '{}'", projectId, e);
                    return Mono.just(new RecentActivity(List.of()));
                });
    }

    private Mono<List<RecentActivityItem>> fetchExperiments(UUID projectId) {
        var criteria = ExperimentSearchCriteria.builder()
                .entityType(EntityType.TRACE)
                .projectId(projectId)
                .sortingFields(List.of())
                .types(Set.of(ExperimentType.REGULAR))
                .build();

        return experimentService.find(1, SOURCE_QUERY_LIMIT, criteria)
                .map(page -> page.content().stream()
                        .map(e -> new RecentActivityItem(
                                ActivityType.EXPERIMENT, e.id(), e.name(), e.createdAt(), e.datasetId()))
                        .toList())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent experiments for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchOptimizations(UUID projectId) {
        var criteria = OptimizationSearchCriteria.builder()
                .entityType(EntityType.TRACE)
                .projectId(projectId)
                .build();

        return optimizationService.find(1, SOURCE_QUERY_LIMIT, criteria)
                .map(page -> page.content().stream()
                        .map(o -> new RecentActivityItem(
                                ActivityType.OPTIMIZATION, o.id(), o.datasetName(), o.createdAt()))
                        .toList())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent optimizations for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchAlertEvents(UUID projectId) {
        var criteria = LogCriteria.builder()
                .size(SOURCE_QUERY_LIMIT)
                .page(1)
                .markers(Map.of("project_id", projectId.toString()))
                .build();

        return alertEventLogsDAO.findLogs(criteria)
                .map(logItem -> new RecentActivityItem(
                        ActivityType.ALERT_EVENT,
                        UUID.fromString(logItem.markers().get("alert_id")),
                        logItem.message(),
                        logItem.timestamp()))
                .collectList()
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent alert events for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<RecentActivityItem>> fetchJdbiSources(String workspaceId, UUID projectId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            List<RecentActivityItem> items = new ArrayList<>();

            var datasetVersionDao = handle.attach(DatasetVersionDAO.class);
            for (var type : List.of("dataset", "evaluation_suite")) {
                var activityType = "dataset".equals(type)
                        ? ActivityType.DATASET_VERSION
                        : ActivityType.TEST_SUITE_VERSION;

                var recent = datasetVersionDao.findRecentActivityByProjectId(
                        workspaceId, projectId, type, SOURCE_QUERY_LIMIT);
                for (var r : recent) {
                    items.add(new RecentActivityItem(
                            activityType, r.datasetId(), r.datasetName(), r.createdAt()));
                }
            }

            var agentConfigDao = handle.attach(AgentConfigDAO.class);
            var blueprints = agentConfigDao.getBlueprintHistory(workspaceId, projectId, SOURCE_QUERY_LIMIT, 0);
            for (var bp : blueprints) {
                items.add(new RecentActivityItem(
                        ActivityType.AGENT_CONFIG_VERSION, bp.id(), bp.name(), bp.createdAt()));
            }

            return items;
        })).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to fetch recent JDBI sources for project '{}'", projectId, e);
                    return Mono.just(List.of());
                });
    }
}
