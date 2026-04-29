package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.ExperimentProjectMigrationConfig;
import com.comet.opik.infrastructure.MigrationConfig;
import com.google.common.collect.Lists;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListener.FINISHED_STATUSES;
import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class ExperimentProjectMigrationService {

    public static final String METRIC_NAMESPACE = "opik.migration.experiment_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_CERTAIN = Attributes.of(RESULT_KEY, "no_certain_experiments");
    private static final Attributes RESULT_ALL_SKIPPED = Attributes.of(RESULT_KEY, "all_skipped_deleted_project");
    private static final Attributes SKIP_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"), "deleted_project");

    /**
     * Process-local set of workspaces whose only certain experiments point to deleted projects.
     * Without this, every cycle would re-attempt the same workspaces and run an expensive
     * 3-table JOIN for nothing. The set is reset on JVM restart, so a redeployed process retries
     * each previously-trapped workspace once before re-trapping it. Operators can permanently ban
     * a workspace via the {@code migration.excludedWorkspaceIds} config.
     */
    private static final Set<String> TRAPPED_WORKSPACE_IDS = ConcurrentHashMap.newKeySet();

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull ProjectService projectService;
    private final @NonNull ExperimentAggregationPublisher experimentAggregationPublisher;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull ExperimentProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;
    private final @NonNull ExperimentDenormalizationConfig denormalizationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter experimentsSkipped;
    private final LongHistogram batchSize;

    @Inject
    public ExperimentProjectMigrationService(
            @NonNull ExperimentDAO experimentDAO,
            @NonNull ExperimentItemService experimentItemService,
            @NonNull ProjectService projectService,
            @NonNull ExperimentAggregationPublisher experimentAggregationPublisher,
            @NonNull WorkspaceVersionService workspaceVersionService,
            @NonNull @Config("experimentProjectMigration") ExperimentProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig,
            @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig denormalizationConfig) {
        this.experimentDAO = experimentDAO;
        this.experimentItemService = experimentItemService;
        this.projectService = projectService;
        this.experimentAggregationPublisher = experimentAggregationPublisher;
        this.workspaceVersionService = workspaceVersionService;
        this.config = config;
        this.migrationConfig = migrationConfig;
        this.denormalizationConfig = denormalizationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with eligible experiments found per cycle")
                .ofLongs()
                .build();
        this.cycleTrappedWorkspaces = meter
                .gaugeBuilder("%s.cycle.trapped_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces locally skipped because all their certain experiments point to deleted projects")
                .ofLongs()
                .build();
        this.workspaceDuration = meter
                .histogramBuilder("%s.workspace.duration".formatted(METRIC_NAMESPACE))
                .setDescription("Duration of a single workspace migration, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.experimentsSkipped = meter
                .counterBuilder("%s.experiments.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Total number of experiments skipped during migration, tagged by reason")
                .build();
        this.batchSize = meter
                .histogramBuilder("%s.batch.size".formatted(METRIC_NAMESPACE))
                .setDescription("Size of each successful ClickHouse INSERT batch")
                .ofLongs()
                .build();
    }

    public Mono<Void> runMigrationCycle() {
        cycleTrappedWorkspaces.set(TRAPPED_WORKSPACE_IDS.size());
        log.info(
                "Starting experiment project migration cycle, workspacesPerRun='{}', batchSize='{}', trappedWorkspaces='{}'",
                config.workspacesPerRun(), config.experimentBatchSize(), TRAPPED_WORKSPACE_IDS.size());
        var excludedWorkspaceIds = Stream.concat(
                migrationConfig.getExcludedWorkspaceIds().stream(),
                TRAPPED_WORKSPACE_IDS.stream())
                .collect(Collectors.toUnmodifiableSet());
        return experimentDAO.findEligibleExperimentWorkspaces(excludedWorkspaceIds, config.workspacesPerRun())
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, ""))
                .collectList()
                .flatMapMany(eligibleWorkspaces -> {
                    cycleEligibleWorkspaces.record(eligibleWorkspaces.size());
                    if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
                        log.info("No workspaces with eligible experiments found, consider disabling the job");
                        return Flux.empty();
                    }
                    log.info("Found workspaces with eligible experiments, count='{}'", eligibleWorkspaces.size());
                    return Flux.fromIterable(eligibleWorkspaces)
                            .concatMap(workspace -> migrateWorkspace(
                                    workspace.workspaceId(),
                                    workspace.experimentsCount(),
                                    config.experimentBatchSize()));
                })
                .then();
    }

    private Mono<Boolean> migrateWorkspace(String workspaceId, long experimentsCount, int batchSize) {
        log.info("Starting workspace migration, workspaceId='{}', experimentsCount='{}'",
                workspaceId, experimentsCount);
        var workspaceStartMillis = System.currentTimeMillis();
        return experimentDAO.computeExperimentProjectMapping()
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                .collectList()
                .flatMap(mappings -> {
                    if (CollectionUtils.isEmpty(mappings)) {
                        log.info("No certain experiments to migrate, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_CERTAIN, workspaceStartMillis);
                        return Mono.empty();
                    }
                    return migrateValidatedMappings(workspaceId, mappings, batchSize, workspaceStartMillis);
                })
                .onErrorResume(throwable -> {
                    log.error("Workspace migration failed, will retry next cycle, workspaceId='{}'",
                            workspaceId, throwable);
                    recordWorkspaceDuration(RESULT_ERROR, workspaceStartMillis);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> migrateValidatedMappings(
            String workspaceId, List<ExperimentProjectMapping> mappings, int batchSize, long workspaceStartMillis) {
        var inferredProjectIds = mappings.stream()
                .map(ExperimentProjectMapping::projectId)
                .collect(Collectors.toUnmodifiableSet());
        return Mono.fromCallable(() -> projectService.findByIds(workspaceId, inferredProjectIds))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existingProjects -> {
                    var validProjectIds = existingProjects.stream()
                            .map(Project::id)
                            .collect(Collectors.toUnmodifiableSet());
                    var validated = mappings.stream()
                            .filter(mapping -> validProjectIds.contains(mapping.projectId()))
                            .toList();
                    var skippedDeleted = mappings.size() - validated.size();
                    if (skippedDeleted > 0) {
                        log.info("Skipping experiments with deleted project, workspaceId='{}', count='{}'",
                                workspaceId, skippedDeleted);
                        experimentsSkipped.add(skippedDeleted, SKIP_REASON_DELETED_PROJECT);
                    }
                    if (CollectionUtils.isEmpty(validated)) {
                        log.info(
                                "All certain experiments point to deleted projects, marking workspace as trapped, workspaceId='{}'",
                                workspaceId);
                        TRAPPED_WORKSPACE_IDS.add(workspaceId);
                        recordWorkspaceDuration(RESULT_ALL_SKIPPED, workspaceStartMillis);
                        return Mono.empty();
                    }
                    var byProject = validated.stream()
                            .collect(Collectors.groupingBy(ExperimentProjectMapping::projectId));
                    return Flux.fromIterable(byProject.entrySet())
                            .concatMap(entry -> batchUpdateProjectId(
                                    workspaceId, entry.getKey(), entry.getValue(), batchSize))
                            .then(triggerReaggregation(workspaceId, validated))
                            .then(evictWorkspaceVersionCache(workspaceId))
                            .doOnSuccess(__ -> {
                                log.info(
                                        "Workspace migration completed, workspaceId='{}', migrated='{}', skippedDeletedProject='{}'",
                                        workspaceId, validated.size(), skippedDeleted);
                                recordWorkspaceDuration(RESULT_MIGRATED, workspaceStartMillis);
                            });
                });
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }

    private Mono<Long> batchUpdateProjectId(
            String workspaceId, UUID projectId, List<ExperimentProjectMapping> experiments, int maxBatchSize) {
        return Flux.fromIterable(Lists.partition(experiments, maxBatchSize))
                .concatMap(batch -> {
                    var experimentIds = batch.stream()
                            .map(ExperimentProjectMapping::experimentId)
                            .collect(Collectors.toUnmodifiableSet());
                    log.debug("Updating experiment batch, workspaceId='{}', projectId='{}', count='{}'",
                            workspaceId, projectId, experimentIds.size());
                    return experimentDAO.batchSetProjectId(experimentIds, projectId)
                            .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                            .doOnSuccess(rowsUpdated -> batchSize.record(experimentIds.size()));
                })
                .reduce(0L, Long::sum);
    }

    private Mono<Void> triggerReaggregation(String workspaceId, List<ExperimentProjectMapping> migrated) {
        if (!denormalizationConfig.isEnabled()) {
            log.debug("Skipping reaggregation: experiment denormalization disabled, workspaceId='{}'", workspaceId);
            return Mono.empty();
        }
        var experimentIds = migrated.stream()
                .map(ExperimentProjectMapping::experimentId)
                .collect(Collectors.toUnmodifiableSet());
        return experimentItemService.filterExperimentIdsByStatus(experimentIds, FINISHED_STATUSES)
                .collect(Collectors.toUnmodifiableSet())
                .flatMap(finished -> {
                    if (finished.isEmpty()) {
                        log.info("No finished experiments to reaggregate, workspaceId='{}', candidateCount='{}'",
                                workspaceId, experimentIds.size());
                        return Mono.empty();
                    }
                    return experimentAggregationPublisher.publish(finished, workspaceId, SYSTEM_USER);
                })
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                .onErrorResume(throwable -> {
                    log.warn("Failed to trigger experiment reaggregation, workspaceId='{}', experimentIds.size='{}'",
                            workspaceId, experimentIds.size(), throwable);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> evictWorkspaceVersionCache(String workspaceId) {
        return workspaceVersionService.evictCache(workspaceId)
                .onErrorResume(throwable -> {
                    log.warn("Failed to evict workspace version cache, workspaceId='{}'", workspaceId, throwable);
                    return Mono.just(false);
                });
    }
}
