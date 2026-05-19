package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.ExperimentProjectMigrationConfig;
import com.comet.opik.infrastructure.MigrationConfig;
import com.google.common.collect.Lists;
import io.dropwizard.lifecycle.Managed;
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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListener.FINISHED_STATUSES;
import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class ExperimentProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.experiment_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_CERTAIN = Attributes.of(RESULT_KEY, "no_certain_experiments");
    private static final Attributes RESULT_ALL_SKIPPED = Attributes.of(RESULT_KEY, "all_skipped_deleted_project");
    private static final Attributes SKIP_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"), "deleted_project");

    private static final String TRAPPED_REASON_DELETED_PROJECT = "deleted_project";

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull ProjectService projectService;
    private final @NonNull ExperimentAggregationPublisher experimentAggregationPublisher;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull ExperimentProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;
    private final @NonNull ExperimentDenormalizationConfig denormalizationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter experimentsSkipped;
    private final LongHistogram batchSize;

    /**
     * Dedicated bounded-elastic scheduler isolating the migration's blocking JDBC work and
     * its post-collect CPU-heavy lambdas from the shared {@link Schedulers#boundedElastic()}
     * and from the reactive client (R2DBC/Redisson) event loops. Sized for the sequential
     * concatMap flow; daemon threads so JVM shutdown is never blocked by the migration pool.
     */
    private volatile Scheduler migrationScheduler;

    @Inject
    public ExperimentProjectMigrationService(
            @NonNull ExperimentDAO experimentDAO,
            @NonNull ExperimentItemService experimentItemService,
            @NonNull ProjectService projectService,
            @NonNull ExperimentAggregationPublisher experimentAggregationPublisher,
            @NonNull WorkspaceVersionService workspaceVersionService,
            @NonNull WorkspacesService workspacesService,
            @NonNull @Config("experimentProjectMigration") ExperimentProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig,
            @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig denormalizationConfig) {
        this.experimentDAO = experimentDAO;
        this.experimentItemService = experimentItemService;
        this.projectService = projectService;
        this.experimentAggregationPublisher = experimentAggregationPublisher;
        this.workspaceVersionService = workspaceVersionService;
        this.workspacesService = workspacesService;
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
        this.cycleEnvExcludedWorkspaces = meter
                .gaugeBuilder("%s.cycle.env_excluded_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces excluded from migration via the MIGRATION_EXCLUDED_WORKSPACE_IDS env var. Mirrors trapped_workspaces so the dashboard can show both exclusion paths side by side.")
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

    @Override
    public void start() {
        if (migrationScheduler == null) {
            migrationScheduler = Schedulers.newBoundedElastic(
                    config.schedulerThreadCap(),
                    config.schedulerQueuedTaskCap(),
                    "experiment-project-migration-service",
                    (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized experiment project migration scheduler, threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    config.schedulerThreadCap(), config.schedulerQueuedTaskCap(), config.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Experiment project migration scheduler disposed");
        }
    }

    public Mono<Void> runMigrationCycle() {
        return Mono.fromCallable(() -> {
            var skippedWorkspaceIds = workspacesService.findExperimentProjectMigrationSkippedWorkspaceIds();
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            cycleTrappedWorkspaces.set(skippedWorkspaceIds.size());
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting experiment project migration cycle, workspacesPerRun='{}', batchSize='{}', trappedWorkspaces='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.experimentBatchSize(), skippedWorkspaceIds.size(),
                    envExcludedWorkspaceIds.size());
            return Stream.concat(
                    envExcludedWorkspaceIds.stream(),
                    skippedWorkspaceIds.stream())
                    .collect(Collectors.toUnmodifiableSet());
        })
                .subscribeOn(migrationScheduler)
                .flatMapMany(excludedWorkspaceIds -> experimentDAO
                        .findEligibleExperimentWorkspaces(excludedWorkspaceIds, config.workspacesPerRun())
                        .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, ""))
                        .collectList()
                        // Hop downstream off the ClickHouse R2DBC thread before the per-workspace iteration.
                        .publishOn(migrationScheduler)
                        .flatMapMany(eligibleWorkspaces -> {
                            cycleEligibleWorkspaces.record(eligibleWorkspaces.size());
                            if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
                                log.info("No workspaces with eligible experiments found, consider disabling the job");
                                return Flux.empty();
                            }
                            log.info("Found workspaces with eligible experiments, count='{}'",
                                    eligibleWorkspaces.size());
                            return Flux.fromIterable(eligibleWorkspaces)
                                    // Pin every per-workspace concatMap iteration to migrationScheduler —
                                    // items 2 -> N would otherwise emit on whichever client thread completed
                                    // the previous workspace's reactive tail.
                                    .publishOn(migrationScheduler)
                                    .concatMap(workspace -> migrateWorkspace(
                                            workspace.workspaceId(),
                                            workspace.experimentsCount(),
                                            config.experimentBatchSize()));
                        }))
                .then();
    }

    private Mono<Boolean> migrateWorkspace(String workspaceId, long experimentsCount, int batchSize) {
        log.info("Starting workspace migration, workspaceId='{}', experimentsCount='{}'",
                workspaceId, experimentsCount);
        var workspaceStartMillis = System.currentTimeMillis();
        return experimentDAO.computeExperimentProjectMapping()
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                .collectList()
                // Hop downstream off the ClickHouse R2DBC thread before iterating/grouping the
                // mappings; for a large workspace these CPU lists are non-trivial.
                .publishOn(migrationScheduler)
                .flatMap(mappings -> {
                    if (CollectionUtils.isEmpty(mappings)) {
                        log.info("No certain experiments to migrate, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_CERTAIN, workspaceStartMillis);
                        return Mono.empty();
                    }
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    log.info(
                            "Computed certain experiment project mappings, workspaceId='{}', count='{}', duration='{}'",
                            workspaceId, mappings.size(), duration);
                    return migrateValidatedMappings(workspaceId, mappings, batchSize, workspaceStartMillis);
                })
                .onErrorResume(throwable -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    log.error("Workspace migration failed, will retry next cycle, workspaceId='{}', duration='{}'",
                            workspaceId, duration, throwable);
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
                .subscribeOn(migrationScheduler)
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
                        return Mono
                                .fromRunnable(() -> workspacesService.markExperimentProjectMigrationSkipped(
                                        workspaceId, TRAPPED_REASON_DELETED_PROJECT))
                                .subscribeOn(migrationScheduler)
                                .doFinally(signalType -> recordWorkspaceDuration(
                                        RESULT_ALL_SKIPPED, workspaceStartMillis))
                                .then(Mono.empty());
                    }
                    var byProject = validated.stream()
                            .collect(Collectors.groupingBy(ExperimentProjectMapping::projectId));
                    return Flux.fromIterable(byProject.entrySet())
                            // Pin every per-project concatMap iteration to migrationScheduler — also
                            // covers the CPU inside batchUpdateProjectId (Lists.partition).
                            .publishOn(migrationScheduler)
                            .concatMap(entry -> batchUpdateProjectId(
                                    workspaceId, entry.getKey(), entry.getValue(), batchSize))
                            .doOnComplete(() -> log.info(
                                    "Batch project ID updates completed, workspaceId='{}', projectGroups='{}'",
                                    workspaceId, byProject.size()))
                            .then(triggerReaggregation(workspaceId, validated))
                            .then(evictWorkspaceVersionCache(workspaceId))
                            .doOnSuccess(__ -> {
                                var duration = Duration
                                        .ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                                log.info(
                                        "Workspace migration completed, workspaceId='{}', migrated='{}', skippedDeletedProject='{}', duration='{}'",
                                        workspaceId, validated.size(), skippedDeleted, duration);
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
                // Pin every per-batch concatMap iteration to migrationScheduler — the per-batch
                // stream/collect for items 2 -> N would otherwise run on the R2DBC thread of the
                // previous batchSetProjectId's completion, accumulating on large workspaces.
                .publishOn(migrationScheduler)
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
                // Hop downstream off the ClickHouse R2DBC thread before the publishing hop.
                .publishOn(migrationScheduler)
                .flatMap(finished -> {
                    if (finished.isEmpty()) {
                        log.info("No finished experiments to reaggregate, workspaceId='{}', candidateCount='{}'",
                                workspaceId, experimentIds.size());
                        return Mono.empty();
                    }
                    return experimentAggregationPublisher.publish(finished, workspaceId, SYSTEM_USER)
                            .doOnSuccess(unused -> log.info(
                                    "Reaggregation triggered, workspaceId='{}', finishedCount='{}'",
                                    workspaceId, finished.size()));
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
