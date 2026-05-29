package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.infrastructure.MigrationConfig;
import com.comet.opik.infrastructure.OptimizationProjectMigrationConfig;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * D3 of the V1 → V2 workspace migration. Backfills {@code optimizations.project_id} in ClickHouse
 * from {@code ''} (orphan) to a real project. Mirrors D1 (experiments) and D2 (datasets) — same
 * Quartz job + Managed service + dedicated reactor scheduler shape — with two inference paths
 * instead of one:
 *
 * <ul>
 *   <li><b>Path A (primary)</b>: read distinct {@code experiment.project_id} values for the
 *       optimization's experiments. Captures where the optimization actually ran. Authoritative.
 *   <li><b>Path B (fallback, cross-DB)</b>: read {@code datasets.project_id} for the
 *       optimization's {@code dataset_id}. Used only when Path A yields no signal.
 * </ul>
 *
 * <p>Path A wins over Path B on disagreement (the optimization's own trials reflect its specific
 * run, while a dataset can be shared across many uses). When Path A returns several distinct
 * projects, the dominant project wins — ordered by {@code (count DESC, last_activity DESC,
 * project_id ASC)} so repeated runs produce the same result. This matches the dataset migration
 * (OPIK-6701); the per-product analysis lives in the "Optimization Project Migration — Options
 * Review" Notion doc.
 *
 * <p>Classification buckets:
 *
 * <table>
 *   <tr><th>Bucket</th><th>Action</th></tr>
 *   <tr><td>Certain via experiments (Path A, ≥ 1 project)</td><td>Assign dominant</td></tr>
 *   <tr><td>Certain via dataset (Path B, 1 project)</td><td>Assign inferred</td></tr>
 *   <tr><td>Certain but project deleted</td><td>Assign to Default Project</td></tr>
 *   <tr><td>No inference (Path A=0, Path B=null)</td><td>Assign to Default Project</td></tr>
 * </table>
 *
 * <p>D1 (experiments) and D2 (datasets) must be fully drained before this migration is enabled
 * — Path A reads {@code experiment.project_id} (which D1 sets) and Path B reads
 * {@code datasets.project_id} (which D2 sets). The ordering is enforced by the deployment runbook,
 * not in code.
 */
@Slf4j
@Singleton
public class OptimizationProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.optimization_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ACTIONABLE = Attributes.of(RESULT_KEY, "no_actionable");

    // Reason labels for the `optimizations.assigned_to_default` counter — diagnoses why an
    // optimization ended up in Default Project (inferred project was deleted vs. no inference at
    // all from either Path A or Path B).
    private static final Attributes ASSIGNED_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"),
            "deleted_project");
    private static final Attributes ASSIGNED_REASON_NO_INFERENCE = Attributes.of(stringKey("reason"), "no_inference");

    /**
     * Label on the {@code optimizations.assigned_to_dominant_project} counter that breaks the
     * count down by the number of projects an optimization's experiments referenced.
     */
    private static final AttributeKey<String> DISTINCT_PROJECT_COUNT_KEY = stringKey("distinct_project_count");

    /**
     * Upper bound applied to the {@link #DISTINCT_PROJECT_COUNT_KEY} label value so that an
     * unexpectedly large project count cannot create an unbounded number of metric series. Counts
     * at or above this value share a single label.
     */
    private static final int DISTINCT_PROJECT_COUNT_MAX = 50;

    // Inference-path labels for the `inference.path` diagnostic counter — diagnoses whether Path
    // B (dataset fallback) is doing useful work or Path A (experiments) handles everything in
    // practice. Incremented once per migrated optimization (not for no-inference fall-throughs).
    private static final AttributeKey<String> INFERENCE_PATH_KEY = stringKey("path");
    private static final Attributes INFERENCE_PATH_EXPERIMENTS = Attributes.of(INFERENCE_PATH_KEY, "experiments");
    private static final Attributes INFERENCE_PATH_DATASET = Attributes.of(INFERENCE_PATH_KEY, "dataset");

    private static final String DEFAULT_PROJECT_NAME = ProjectService.DEFAULT_PROJECT;

    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull OptimizationProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter optimizationsAssignedToDefault;
    private final LongCounter optimizationsAssignedToDominantProject;
    private final LongCounter inferencePath;
    private final LongHistogram batchSize;

    /**
     * Dedicated bounded-elastic scheduler isolating the migration's blocking JDBC work and its
     * post-collect CPU-heavy lambdas from the shared {@link Schedulers#boundedElastic()} and from
     * the reactive client (R2DBC/Redisson) event loops. Sized for the sequential concatMap flow;
     * daemon threads so JVM shutdown is never blocked by the migration pool.
     */
    private volatile Scheduler migrationScheduler;

    @Inject
    public OptimizationProjectMigrationService(
            @NonNull OptimizationDAO optimizationDAO,
            @NonNull DatasetService datasetService,
            @NonNull ProjectService projectService,
            @NonNull WorkspaceVersionService workspaceVersionService,
            @NonNull @Config("optimizationProjectMigration") OptimizationProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.optimizationDAO = optimizationDAO;
        this.datasetService = datasetService;
        this.projectService = projectService;
        this.workspaceVersionService = workspaceVersionService;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with eligible optimizations found per cycle")
                .ofLongs()
                .build();
        this.cycleEnvExcludedWorkspaces = meter
                .gaugeBuilder("%s.cycle.env_excluded_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces excluded from migration via the MIGRATION_EXCLUDED_WORKSPACE_IDS env var.")
                .ofLongs()
                .build();
        this.workspaceDuration = meter
                .histogramBuilder("%s.workspace.duration".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Duration of a single workspace migration, tagged by result (migrated / no_actionable / dependencies_pending / error)")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.optimizationsAssignedToDefault = meter
                .counterBuilder("%s.optimizations.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan optimizations assigned to the workspace's Default Project. Diagnostic for the no-inference and deleted-project buckets.")
                .build();
        this.optimizationsAssignedToDominantProject = meter
                .counterBuilder("%s.optimizations.assigned_to_dominant_project".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan optimizations whose experiments referenced multiple projects and were assigned to the dominant one (most referencing experiments). Tagged by distinct_project_count for the multi-project distribution.")
                .build();
        this.inferencePath = meter
                .counterBuilder("%s.inference.path".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of optimizations migrated per inference path (experiments / dataset). Diagnoses whether Path B is doing useful work.")
                .build();
        this.batchSize = meter
                .histogramBuilder("%s.batch.size".formatted(METRIC_NAMESPACE))
                .setDescription("Size of each successful ClickHouse batch update")
                .ofLongs()
                .build();
    }

    @Override
    public void start() {
        if (migrationScheduler == null) {
            migrationScheduler = Schedulers.newBoundedElastic(
                    config.schedulerThreadCap(),
                    config.schedulerQueuedTaskCap(),
                    "optimization-project-migration-service",
                    (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized optimization project migration scheduler, threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    config.schedulerThreadCap(), config.schedulerQueuedTaskCap(), config.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Optimization project migration scheduler disposed");
        }
    }

    public Mono<Void> runMigrationCycle() {
        return Mono.fromCallable(() -> {
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting optimization project migration cycle, workspacesPerRun='{}', batchSize='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.optimizationBatchSize(),
                    envExcludedWorkspaceIds.size());
            return envExcludedWorkspaceIds;
        })
                .subscribeOn(migrationScheduler)
                .flatMapMany(excludedWorkspaceIds -> findEligibleWorkspaces(excludedWorkspaceIds)
                        .collectList()
                        .flatMapMany(eligibleWorkspaces -> {
                            cycleEligibleWorkspaces.record(eligibleWorkspaces.size());
                            if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
                                log.info("No workspaces with eligible optimizations found, "
                                        + "consider disabling the job");
                                return Flux.empty();
                            }
                            log.info("Found workspaces with eligible optimizations, count='{}'",
                                    eligibleWorkspaces.size());
                            return Flux.fromIterable(eligibleWorkspaces)
                                    .concatMap(workspace -> migrateWorkspace(
                                            workspace.workspaceId(),
                                            workspace.optimizationsCount()));
                        }))
                .then();
    }

    private Flux<EligibleOptimizationWorkspace> findEligibleWorkspaces(Set<String> excludedWorkspaceIds) {
        return optimizationDAO.findEligibleOptimizationWorkspaces(excludedWorkspaceIds, config.workspacesPerRun())
                .publishOn(migrationScheduler);
    }

    private Mono<Boolean> migrateWorkspace(String workspaceId, long optimizationsCount) {
        var workspaceStartMillis = System.currentTimeMillis();
        log.info("Starting workspace migration, workspaceId='{}', optimizationsCount='{}'",
                workspaceId, optimizationsCount);
        return findOrphanOptimizations(workspaceId)
                .flatMap(orphans -> {
                    if (CollectionUtils.isEmpty(orphans)) {
                        log.info("No orphan optimizations remain, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_ACTIONABLE, workspaceStartMillis);
                        return Mono.just(false);
                    }
                    return classifyAndMigrate(workspaceId, orphans, workspaceStartMillis);
                })
                .onErrorResume(throwable -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    log.error("Workspace migration failed, will retry next cycle, workspaceId='{}', duration='{}'",
                            workspaceId, duration, throwable);
                    recordWorkspaceDuration(RESULT_ERROR, workspaceStartMillis);
                    return Mono.just(false);
                });
    }

    private Mono<List<OrphanOptimization>> findOrphanOptimizations(String workspaceId) {
        return optimizationDAO.findOrphanOptimizationsInWorkspace(workspaceId)
                .publishOn(migrationScheduler)
                .collectList();
    }

    /**
     * Four-bucket classification: certain-via-experiments (dominant) / certain-via-dataset /
     * certain-deleted → Default / no-inference → Default. Path A wins over Path B on disagreement;
     * Path A returning multiple projects routes to the dominant one (mirroring OPIK-6701).
     */
    private Mono<Boolean> classifyAndMigrate(
            String workspaceId, List<OrphanOptimization> orphans, long workspaceStartMillis) {

        var orphanIds = orphans.stream()
                .map(OrphanOptimization::optimizationId)
                .collect(Collectors.toUnmodifiableSet());
        var orphanDatasetByOptimization = orphans.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OrphanOptimization::optimizationId, OrphanOptimization::datasetId,
                        (existing, duplicate) -> existing));

        return optimizationDAO.computeOptimizationProjectMappingViaExperiments(orphanIds)
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                .publishOn(migrationScheduler)
                .collectList()
                .flatMap(pathAResults -> classifyWithPathAResults(
                        workspaceId, orphanIds, orphanDatasetByOptimization,
                        pathAResults, workspaceStartMillis));
    }

    private Mono<Boolean> classifyWithPathAResults(
            String workspaceId,
            Set<UUID> orphanIds,
            Map<UUID, UUID> orphanDatasetByOptimization,
            List<OptimizationProjectMapping> pathAResults,
            long workspaceStartMillis) {

        var pathAByOptimization = pathAResults.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OptimizationProjectMapping::optimizationId, Function.identity(),
                        (existing, duplicate) -> existing));

        // Bucket 1: certain via experiments (Path A) — collect with their inference path. Any
        // Path A row counts as certain; multi-project rows already carry the dominant project
        // chosen by the COMPUTE_OPTIMIZATION_PROJECT_MAPPING_VIA_EXPERIMENTS ranking.
        // Everything else (no Path A row) falls through to Path B.
        var certainViaExperiments = new ArrayList<InferredMapping>();
        var pathBCandidateOptimizations = new HashSet<UUID>();
        for (var optimizationId : orphanIds) {
            var pathA = pathAByOptimization.get(optimizationId);
            if (pathA == null) {
                pathBCandidateOptimizations.add(optimizationId);
            } else {
                certainViaExperiments.add(new InferredMapping(optimizationId, pathA.projectId(),
                        InferencePath.EXPERIMENTS));
            }
        }

        // Path B: bulk MySQL lookup of datasets.project_id for the remaining orphans.
        var pathBDatasetIds = pathBCandidateOptimizations.stream()
                .map(orphanDatasetByOptimization::get)
                .collect(Collectors.toUnmodifiableSet());

        return Mono.fromCallable(() -> datasetService.findProjectIdsByDatasetIds(pathBDatasetIds, workspaceId))
                .subscribeOn(migrationScheduler)
                .flatMap(datasetProjectByDataset -> {
                    var certainViaDataset = new ArrayList<InferredMapping>();
                    var noInferenceIds = new ArrayList<UUID>();
                    for (var optimizationId : pathBCandidateOptimizations) {
                        var datasetId = orphanDatasetByOptimization.get(optimizationId);
                        var projectId = datasetProjectByDataset.get(datasetId);
                        if (projectId != null) {
                            certainViaDataset.add(new InferredMapping(optimizationId, projectId,
                                    InferencePath.DATASET));
                        } else {
                            noInferenceIds.add(optimizationId);
                        }
                    }
                    var allCertain = new ArrayList<InferredMapping>(
                            certainViaExperiments.size() + certainViaDataset.size());
                    allCertain.addAll(certainViaExperiments);
                    allCertain.addAll(certainViaDataset);
                    return validateAndMigrate(workspaceId, orphanIds.size(), allCertain, noInferenceIds,
                            pathAByOptimization, workspaceStartMillis);
                });
    }

    /**
     * For each validated multi-project Path A assignment, increments
     * {@code optimizations.assigned_to_dominant_project} (labeled by distinct_project_count,
     * capped at {@link #DISTINCT_PROJECT_COUNT_MAX}) and logs the chosen project together with
     * the per-project counts that determined it. Mirrors
     * {@code DatasetProjectMigrationService.recordDominantAssignments}.
     *
     * <p>Called <b>after</b> {@code validateAndMigrate} drops Path A entries whose project was
     * deleted, so the counter only fires for the optimization rows that actually keep the
     * dominant project — entries that get rerouted to Default Project are counted by
     * {@code optimizations.assigned_to_default{reason=deleted_project}} instead.
     */
    private void recordDominantAssignments(String workspaceId, List<InferredMapping> validatedCertain,
            Map<UUID, OptimizationProjectMapping> pathAByOptimization) {
        for (var mapping : validatedCertain) {
            if (mapping.path() != InferencePath.EXPERIMENTS) {
                continue;
            }
            var pathA = pathAByOptimization.get(mapping.optimizationId());
            if (pathA == null || pathA.distinctProjectCount() <= 1) {
                continue;
            }
            long boundedCount = Math.min(pathA.distinctProjectCount(), DISTINCT_PROJECT_COUNT_MAX);
            optimizationsAssignedToDominantProject.add(1,
                    Attributes.of(DISTINCT_PROJECT_COUNT_KEY, Long.toString(boundedCount)));
            log.info(
                    "Assigning dominant project to optimization, workspaceId='{}', optimizationId='{}', chosenProjectId='{}', distinctProjectCount='{}', projectBreakdown='{}'",
                    workspaceId, pathA.optimizationId(), pathA.projectId(), pathA.distinctProjectCount(),
                    pathA.projectBreakdown());
        }
    }

    /**
     * Validate inferred project IDs against MySQL — rows whose project was deleted get rerouted
     * to Default Project (mirroring D1's OPIK-6579 tail behavior). Then resolves Default Project
     * once via {@link ProjectService#getOrCreate} (auto-provisions if missing) and groups all
     * actionable mappings into a single batch INSERT per project.
     */
    private Mono<Boolean> validateAndMigrate(
            String workspaceId,
            int totalOrphans,
            List<InferredMapping> certainCandidates,
            List<UUID> noInferenceIds,
            Map<UUID, OptimizationProjectMapping> pathAByOptimization,
            long workspaceStartMillis) {

        return Mono.fromCallable(() -> {
            if (certainCandidates.isEmpty()) {
                return List.<InferredMapping>of();
            }
            var inferredProjectIds = certainCandidates.stream()
                    .map(InferredMapping::projectId)
                    .collect(Collectors.toUnmodifiableSet());
            var existingProjectIds = projectService.findByIds(workspaceId, inferredProjectIds).stream()
                    .map(Project::id)
                    .collect(Collectors.toUnmodifiableSet());
            return certainCandidates.stream()
                    .filter(mapping -> existingProjectIds.contains(mapping.projectId()))
                    .toList();
        })
                .subscribeOn(migrationScheduler)
                .flatMap(validatedCertain -> {
                    var certainDeletedMappings = certainCandidates.stream()
                            .filter(mapping -> !validatedCertain.contains(mapping))
                            .toList();
                    recordDominantAssignments(workspaceId, validatedCertain, pathAByOptimization);
                    return resolveDefaultProjectAndMigrate(
                            workspaceId, totalOrphans, validatedCertain, certainDeletedMappings,
                            noInferenceIds, workspaceStartMillis);
                });
    }

    /**
     * Reroutes certain-deleted and no-inference orphans to the workspace's Default Project. Uses
     * {@link ProjectService#getOrCreate} (the same path trace ingestion uses), so a missing
     * Default Project is provisioned in-line. The lookup is skipped entirely when neither bucket
     * has rows.
     */
    private Mono<Boolean> resolveDefaultProjectAndMigrate(
            String workspaceId,
            int totalOrphans,
            List<InferredMapping> validatedCertain,
            List<InferredMapping> certainDeletedMappings,
            List<UUID> noInferenceIds,
            long workspaceStartMillis) {

        int needsDefault = certainDeletedMappings.size() + noInferenceIds.size();
        if (needsDefault == 0) {
            // Happy path: only validated-certain rows (or nothing actionable). Skip the
            // getOrCreate call entirely so we never auto-provision a Default Project for
            // workspaces that don't need one.
            return finalizeWorkspace(workspaceId, totalOrphans, new ArrayList<>(validatedCertain),
                    certainDeletedMappings.size(), 0, workspaceStartMillis);
        }
        return Mono
                .fromCallable(() -> projectService.getOrCreate(workspaceId, DEFAULT_PROJECT_NAME, SYSTEM_USER).id())
                .subscribeOn(migrationScheduler)
                .flatMap(defaultProjectId -> {
                    var allMappings = new ArrayList<>(validatedCertain);
                    for (var deleted : certainDeletedMappings) {
                        // Preserve the original inference path label so the diagnostic counter
                        // still records whether Path A or Path B produced the (now-rerouted)
                        // signal.
                        allMappings.add(new InferredMapping(deleted.optimizationId(), defaultProjectId,
                                deleted.path()));
                    }
                    for (var id : noInferenceIds) {
                        // No-inference reroutes have no Path A/B signal; the counter is not
                        // bumped for these.
                        allMappings.add(new InferredMapping(id, defaultProjectId, null));
                    }
                    if (!certainDeletedMappings.isEmpty()) {
                        optimizationsAssignedToDefault.add(certainDeletedMappings.size(),
                                ASSIGNED_REASON_DELETED_PROJECT);
                        log.info(
                                "Assigning deleted-project optimizations to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, certainDeletedMappings.size());
                    }
                    if (!noInferenceIds.isEmpty()) {
                        optimizationsAssignedToDefault.add(noInferenceIds.size(), ASSIGNED_REASON_NO_INFERENCE);
                        log.info(
                                "Assigning no-inference optimizations to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, noInferenceIds.size());
                    }
                    return finalizeWorkspace(workspaceId, totalOrphans, allMappings,
                            certainDeletedMappings.size(), needsDefault, workspaceStartMillis);
                });
    }

    private Mono<Boolean> finalizeWorkspace(
            String workspaceId,
            int totalOrphans,
            List<InferredMapping> mappings,
            int certainDeleted,
            int assignedToDefault,
            long workspaceStartMillis) {

        return migrateBatch(workspaceId, mappings)
                .flatMap(migrated -> migrated
                        ? evictWorkspaceVersionCache(workspaceId).thenReturn(true)
                        : Mono.just(false))
                .map(__ -> mappings.isEmpty() ? RESULT_NO_ACTIONABLE : RESULT_MIGRATED)
                .doOnSuccess(resultAttrs -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    int validatedCertainCount = mappings.size() - assignedToDefault;
                    log.info(
                            "Workspace migration completed, workspaceId='{}', totalOrphans='{}', migrated='{}' (certain='{}', defaultProject='{}'), skippedDeletedProject='{}', duration='{}'",
                            workspaceId, totalOrphans, mappings.size(), validatedCertainCount, assignedToDefault,
                            certainDeleted, duration);
                    recordWorkspaceDuration(resultAttrs, workspaceStartMillis);
                })
                .thenReturn(true);
    }

    private Mono<Boolean> migrateBatch(String workspaceId, List<InferredMapping> mappings) {
        if (mappings.isEmpty()) {
            return Mono.just(false);
        }
        var byProject = mappings.stream()
                .collect(Collectors.groupingBy(InferredMapping::projectId));
        return Flux.fromIterable(byProject.entrySet())
                .concatMap(entry -> writeBatchesForProject(workspaceId, entry.getKey(), entry.getValue()))
                .then(Mono.just(true));
    }

    private Mono<Void> writeBatchesForProject(String workspaceId, UUID projectId, List<InferredMapping> mappings) {
        return Flux.fromIterable(Lists.partition(mappings, config.optimizationBatchSize()))
                .concatMap(batch -> {
                    var ids = batch.stream()
                            .map(InferredMapping::optimizationId)
                            .collect(Collectors.toUnmodifiableSet());
                    return optimizationDAO.batchSetProjectId(ids, projectId)
                            .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                            .doOnSuccess(rowsUpdated -> {
                                batchSize.record(batch.size());
                                recordInferencePaths(batch);
                                log.debug(
                                        "Updated optimization batch, workspaceId='{}', projectId='{}', count='{}', rowsUpdated='{}'",
                                        workspaceId, projectId, batch.size(), rowsUpdated);
                            });
                })
                .then();
    }

    private void recordInferencePaths(List<InferredMapping> batch) {
        long viaExperiments = batch.stream().filter(m -> m.path() == InferencePath.EXPERIMENTS).count();
        long viaDataset = batch.stream().filter(m -> m.path() == InferencePath.DATASET).count();
        if (viaExperiments > 0) {
            inferencePath.add(viaExperiments, INFERENCE_PATH_EXPERIMENTS);
        }
        if (viaDataset > 0) {
            inferencePath.add(viaDataset, INFERENCE_PATH_DATASET);
        }
    }

    private enum InferencePath {
        EXPERIMENTS,
        DATASET
    }

    private record InferredMapping(UUID optimizationId, UUID projectId, InferencePath path) {
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }

    private Mono<Boolean> evictWorkspaceVersionCache(String workspaceId) {
        return workspaceVersionService.evictCache(workspaceId)
                .onErrorResume(throwable -> {
                    log.warn("Failed to evict workspace version cache, workspaceId='{}'", workspaceId, throwable);
                    return Mono.just(false);
                });
    }

}
