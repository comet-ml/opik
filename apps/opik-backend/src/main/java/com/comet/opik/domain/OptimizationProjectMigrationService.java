package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.workspaces.MigrationSkipReasonCount;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.domain.workspaces.WorkspacesService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * D3 of the V1 → V2 workspace migration. Backfills {@code optimizations.project_id} in ClickHouse
 * from {@code ''} (orphan) to a real project. Mirrors D1 (experiments) and D2 (datasets) — same
 * Quartz job + Managed service + dedicated reactor scheduler + persisted skip-state shape — with
 * two inference paths instead of one:
 *
 * <ul>
 *   <li><b>Path A (primary)</b>: read distinct {@code experiment.project_id} values for the
 *       optimization's experiments. Captures where the optimization actually ran. Authoritative.
 *   <li><b>Path B (fallback, cross-DB)</b>: read {@code datasets.project_id} for the
 *       optimization's {@code dataset_id}. Used only when Path A yields no signal.
 * </ul>
 *
 * <p>Path A wins on disagreement (a dataset is shared across many uses; the optimization's own
 * experiments reflect its specific run). When neither path yields a single project, the
 * optimization is classified into one of five buckets:
 *
 * <table>
 *   <tr><th>Bucket</th><th>Action</th></tr>
 *   <tr><td>Certain via experiments (Path A, 1 project)</td><td>Assign inferred</td></tr>
 *   <tr><td>Certain via dataset (Path B, 1 project)</td><td>Assign inferred</td></tr>
 *   <tr><td>Certain but project deleted</td><td>Assign to Default Project</td></tr>
 *   <tr><td>No inference (Path A=0, Path B=null)</td><td>Assign to Default Project</td></tr>
 *   <tr><td>Ambiguous (Path A &gt;1)</td><td>Skip</td></tr>
 * </table>
 *
 * <p>Trap conditions persisted in {@code workspaces.optimization_project_migration_skip_reason}:
 * {@code all_ambiguous} (every remaining orphan is ambiguous). {@code deleted_project} and
 * {@code default_project_missing} are kept as defensive constants — after the policy alignment
 * with D1/D2, neither is emitted by the active code paths (deleted projects reroute to Default
 * Project, missing Default Project is auto-provisioned).
 *
 * <p>The cycle refuses to run when D1 (experiments) or D2 (datasets) have V1 work pending for a
 * given workspace, unless {@code allowBeforeDependencies} is set. The runbook documents the order
 * strictly; this is the code-level safety net.
 */
@Slf4j
@Singleton
public class OptimizationProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.optimization_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ACTIONABLE = Attributes.of(RESULT_KEY, "no_actionable");
    private static final Attributes RESULT_ALL_SKIPPED_AMBIGUOUS = Attributes.of(RESULT_KEY, "all_skipped_ambiguous");
    private static final Attributes RESULT_DEPENDENCIES_PENDING = Attributes.of(RESULT_KEY, "dependencies_pending");

    // Skip-reason labels for the `optimizations.skipped` counter. Only `ambiguous` is emitted
    // today; `deleted_project` is kept as a defensive constant in case a future code path needs
    // it (e.g. a dry-run tool that distinguishes "would have been deleted" from "would have been
    // assigned to default").
    @SuppressWarnings("unused")
    private static final Attributes SKIP_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"), "deleted_project");
    private static final Attributes SKIP_REASON_AMBIGUOUS = Attributes.of(stringKey("reason"), "ambiguous");

    // Reason labels for the `optimizations.assigned_to_default` counter — diagnoses why an
    // optimization ended up in Default Project (inferred project was deleted vs. no inference at
    // all from either Path A or Path B).
    private static final Attributes ASSIGNED_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"),
            "deleted_project");
    private static final Attributes ASSIGNED_REASON_NO_INFERENCE = Attributes.of(stringKey("reason"), "no_inference");

    // Inference-path labels for the `inference.path` diagnostic counter — diagnoses whether Path
    // B (dataset fallback) is doing useful work or Path A (experiments) handles everything in
    // practice. Incremented once per migrated optimization (not for ambiguous or no-inference).
    private static final AttributeKey<String> INFERENCE_PATH_KEY = stringKey("path");
    private static final Attributes INFERENCE_PATH_EXPERIMENTS = Attributes.of(INFERENCE_PATH_KEY, "experiments");
    private static final Attributes INFERENCE_PATH_DATASET = Attributes.of(INFERENCE_PATH_KEY, "dataset");

    private static final String TRAPPED_REASON_ALL_AMBIGUOUS = "all_ambiguous";
    // Defensive constants — neither is emitted by the active code paths after the policy
    // alignment with D1/D2 (Default Project is auto-created via getOrCreate; certain-deleted
    // optimizations get reassigned to Default Project rather than trapping the workspace).
    // Surfaced through the gauge tag set so legacy rows persisted before any future policy
    // change still bucket.
    private static final String TRAPPED_REASON_DELETED_PROJECT = "deleted_project";
    private static final String TRAPPED_REASON_DEFAULT_PROJECT_MISSING = "default_project_missing";

    private static final AttributeKey<String> TRAP_REASON_KEY = stringKey("reason");
    private static final Set<String> KNOWN_TRAP_REASONS = Set.of(
            TRAPPED_REASON_DELETED_PROJECT, TRAPPED_REASON_ALL_AMBIGUOUS, TRAPPED_REASON_DEFAULT_PROJECT_MISSING);

    private static final String DEFAULT_PROJECT_NAME = ProjectService.DEFAULT_PROJECT;

    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull OptimizationProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter optimizationsSkipped;
    private final LongCounter optimizationsAssignedToDefault;
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
            @NonNull ExperimentDAO experimentDAO,
            @NonNull DatasetService datasetService,
            @NonNull ProjectService projectService,
            @NonNull WorkspaceVersionService workspaceVersionService,
            @NonNull WorkspacesService workspacesService,
            @NonNull @Config("optimizationProjectMigration") OptimizationProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.optimizationDAO = optimizationDAO;
        this.experimentDAO = experimentDAO;
        this.datasetService = datasetService;
        this.projectService = projectService;
        this.workspaceVersionService = workspaceVersionService;
        this.workspacesService = workspacesService;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with eligible optimizations found per cycle")
                .ofLongs()
                .build();
        this.cycleTrappedWorkspaces = meter
                .gaugeBuilder("%s.cycle.trapped_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces locally skipped because of a persisted trap, tagged by reason "
                                + "(all_ambiguous / deleted_project / default_project_missing / other)")
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
                .setDescription(
                        "Duration of a single workspace migration, tagged by result (migrated / no_actionable / all_skipped_ambiguous / dependencies_pending / error)")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.optimizationsSkipped = meter
                .counterBuilder("%s.optimizations.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Total number of optimizations skipped during migration, tagged by reason")
                .build();
        this.optimizationsAssignedToDefault = meter
                .counterBuilder("%s.optimizations.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan optimizations assigned to the workspace's Default Project. Diagnostic for the no-inference and deleted-project buckets.")
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
            var skippedWorkspaceIds = workspacesService.findOptimizationProjectMigrationSkippedWorkspaceIds();
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            recordTrappedWorkspacesByReason(workspacesService.countOptimizationProjectMigrationSkippedByReason());
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting optimization project migration cycle, workspacesPerRun='{}', batchSize='{}', trappedWorkspaces='{}', envExcludedWorkspaces='{}', allowBeforeDependencies='{}'",
                    config.workspacesPerRun(), config.optimizationBatchSize(), skippedWorkspaceIds.size(),
                    envExcludedWorkspaceIds.size(), config.allowBeforeDependencies());
            return Stream.concat(
                    envExcludedWorkspaceIds.stream(),
                    skippedWorkspaceIds.stream())
                    .collect(Collectors.toUnmodifiableSet());
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
        return checkDependencies(workspaceId)
                .flatMap(dependenciesOk -> {
                    if (!dependenciesOk) {
                        log.warn(
                                "Skipping workspace because D1 (experiments) or D2 (datasets) still have V1 work pending; set optimizationProjectMigration.allowBeforeDependencies=true to override (workspaceId='{}')",
                                workspaceId);
                        recordWorkspaceDuration(RESULT_DEPENDENCIES_PENDING, workspaceStartMillis);
                        return Mono.just(false);
                    }
                    return findOrphanOptimizations(workspaceId)
                            .flatMap(orphans -> {
                                if (CollectionUtils.isEmpty(orphans)) {
                                    log.info("No orphan optimizations remain, workspaceId='{}'", workspaceId);
                                    recordWorkspaceDuration(RESULT_NO_ACTIONABLE, workspaceStartMillis);
                                    return Mono.just(false);
                                }
                                return classifyAndMigrate(workspaceId, orphans, workspaceStartMillis);
                            });
                })
                .onErrorResume(throwable -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    log.error("Workspace migration failed, will retry next cycle, workspaceId='{}', duration='{}'",
                            workspaceId, duration, throwable);
                    recordWorkspaceDuration(RESULT_ERROR, workspaceStartMillis);
                    return Mono.just(false);
                });
    }

    /**
     * Per-workspace dependency guard: refuse to run if D1 (experiments) or D2 (datasets) still
     * carry V1 work for this workspace. Operators can override via
     * {@code allowBeforeDependencies} (intended for tests and local dev only — production relies
     * on the runbook ordering).
     */
    private Mono<Boolean> checkDependencies(String workspaceId) {
        if (config.allowBeforeDependencies()) {
            return Mono.just(true);
        }
        return experimentDAO.hasVersion1Experiments(workspaceId, DemoData.EXPERIMENTS)
                .flatMap(d1Pending -> {
                    if (d1Pending) {
                        return Mono.just(false);
                    }
                    return Mono.fromCallable(() -> !datasetService.hasVersion1Datasets(workspaceId))
                            .subscribeOn(migrationScheduler);
                });
    }

    private Mono<List<OrphanOptimization>> findOrphanOptimizations(String workspaceId) {
        return optimizationDAO.findOrphanOptimizationsInWorkspace(workspaceId)
                .publishOn(migrationScheduler)
                .collectList();
    }

    /**
     * Five-bucket classification: certain-via-experiments / certain-via-dataset / certain-deleted
     * → Default / no-inference → Default / ambiguous → skip. Path A wins over Path B on
     * disagreement.
     */
    private Mono<Boolean> classifyAndMigrate(
            String workspaceId, List<OrphanOptimization> orphans, long workspaceStartMillis) {

        var orphanIds = orphans.stream()
                .map(OrphanOptimization::optimizationId)
                .collect(Collectors.toUnmodifiableSet());
        var orphanDatasetByOptimization = orphans.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OrphanOptimization::optimizationId, OrphanOptimization::datasetId, (a, b) -> a));

        return optimizationDAO.computeOptimizationProjectMappingViaExperiments(workspaceId, orphanIds)
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
                        (a, b) -> a));

        // Bucket 1: certain via experiments (Path A, distinct = 1) — collect with their inference path.
        // Bucket 5: ambiguous (Path A, distinct > 1) — accumulate count, skip.
        // For everything else (Path A absent), fall through to Path B.
        var certainViaExperiments = new ArrayList<InferredMapping>();
        var pathBCandidateOptimizations = new HashSet<UUID>();
        int ambiguousAcc = 0;
        for (var optimizationId : orphanIds) {
            var pathA = pathAByOptimization.get(optimizationId);
            if (pathA == null) {
                pathBCandidateOptimizations.add(optimizationId);
            } else if (pathA.distinctProjectCount() == 1) {
                certainViaExperiments.add(new InferredMapping(optimizationId, pathA.projectId(),
                        InferencePath.EXPERIMENTS));
            } else {
                ambiguousAcc++;
            }
        }
        final int ambiguousCount = ambiguousAcc;
        if (ambiguousCount > 0) {
            optimizationsSkipped.add(ambiguousCount, SKIP_REASON_AMBIGUOUS);
            log.info("Skipping ambiguous optimizations (multi-project), workspaceId='{}', count='{}'",
                    workspaceId, ambiguousCount);
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
                            ambiguousCount, workspaceStartMillis);
                });
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
            int ambiguousCount,
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
                    return resolveDefaultProjectAndMigrate(
                            workspaceId, totalOrphans, validatedCertain, certainDeletedMappings,
                            noInferenceIds, ambiguousCount, workspaceStartMillis);
                });
    }

    /**
     * Reroutes certain-deleted and no-inference orphans to the workspace's Default Project. Uses
     * {@link ProjectService#getOrCreate} (the same path trace ingestion uses), so a missing
     * Default Project is provisioned in-line rather than trapping the workspace. The lookup is
     * skipped entirely when neither bucket has rows.
     */
    private Mono<Boolean> resolveDefaultProjectAndMigrate(
            String workspaceId,
            int totalOrphans,
            List<InferredMapping> validatedCertain,
            List<InferredMapping> certainDeletedMappings,
            List<UUID> noInferenceIds,
            int ambiguousCount,
            long workspaceStartMillis) {

        int needsDefault = certainDeletedMappings.size() + noInferenceIds.size();
        if (needsDefault == 0) {
            // Happy path: only validated-certain rows (or nothing actionable). Skip the
            // getOrCreate call entirely so we never auto-provision a Default Project for
            // workspaces that don't need one.
            return finalizeWorkspace(workspaceId, totalOrphans, new ArrayList<>(validatedCertain),
                    certainDeletedMappings.size(), ambiguousCount, 0, workspaceStartMillis);
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
                            certainDeletedMappings.size(), ambiguousCount, needsDefault, workspaceStartMillis);
                });
    }

    private Mono<Boolean> finalizeWorkspace(
            String workspaceId,
            int totalOrphans,
            List<InferredMapping> mappings,
            int certainDeleted,
            int ambiguousCount,
            int assignedToDefault,
            long workspaceStartMillis) {

        return migrateBatch(workspaceId, mappings)
                .flatMap(migrated -> migrated
                        ? evictWorkspaceVersionCache(workspaceId).thenReturn(true)
                        : Mono.just(false))
                .flatMap(__ -> resolveTrapReason(totalOrphans, mappings, ambiguousCount)
                        .map(trap -> Mono.fromRunnable(
                                () -> workspacesService.markOptimizationProjectMigrationSkipped(
                                        workspaceId, trap.reason()))
                                .subscribeOn(migrationScheduler)
                                .doOnSubscribe(s -> log.info(
                                        "Trapping workspace, workspaceId='{}', reason='{}', certainDeleted='{}', ambiguous='{}'",
                                        workspaceId, trap.reason(), certainDeleted, ambiguousCount))
                                .then(Mono.just(trap.resultAttrs())))
                        .orElseGet(() -> Mono.just(mappings.isEmpty()
                                ? RESULT_NO_ACTIONABLE
                                : RESULT_MIGRATED)))
                .doOnSuccess(resultAttrs -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    int validatedCertainCount = mappings.size() - assignedToDefault;
                    log.info(
                            "Workspace migration completed, workspaceId='{}', migrated='{}' (certain='{}', defaultProject='{}'), skippedDeletedProject='{}', skippedAmbiguous='{}', duration='{}'",
                            workspaceId, mappings.size(), validatedCertainCount, assignedToDefault,
                            certainDeleted, ambiguousCount, duration);
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
                    return optimizationDAO.batchSetProjectId(workspaceId, ids, projectId)
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

    /**
     * Picks a trap reason if the workspace cannot make further progress next cycle, else empty.
     * After the policy alignment with D1/D2, certain-deleted and no-inference both reroute to
     * Default Project (auto-provisioned), so the only remaining trap is {@code all_ambiguous} —
     * workspaces where every un-migrated orphan has experiments across multiple projects and the
     * CLI/UI tooling is required to disambiguate.
     */
    private Optional<TrapDecision> resolveTrapReason(
            int totalOrphans, List<InferredMapping> mappings, int ambiguousCount) {
        if (mappings.isEmpty() && ambiguousCount == totalOrphans) {
            return Optional.of(new TrapDecision(TRAPPED_REASON_ALL_AMBIGUOUS, RESULT_ALL_SKIPPED_AMBIGUOUS));
        }
        // Post-migration: every remaining orphan is ambiguous → trap so we don't re-query CH
        // every cycle for a workspace that has nothing actionable left.
        int remaining = totalOrphans - mappings.size();
        if (remaining > 0 && ambiguousCount == remaining) {
            return Optional.of(new TrapDecision(TRAPPED_REASON_ALL_AMBIGUOUS, RESULT_ALL_SKIPPED_AMBIGUOUS));
        }
        return Optional.empty();
    }

    private record TrapDecision(String reason, Attributes resultAttrs) {
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

    // Always emits one measurement per known reason (zero when absent) so dashboards see a stable
    // series. Unknown reasons collapse to `other` to bound cardinality.
    private void recordTrappedWorkspacesByReason(List<MigrationSkipReasonCount> counts) {
        var byReason = counts.stream().collect(Collectors.toMap(
                c -> KNOWN_TRAP_REASONS.contains(c.reason()) ? c.reason() : "other",
                MigrationSkipReasonCount::count,
                Long::sum));
        for (var knownReason : KNOWN_TRAP_REASONS) {
            cycleTrappedWorkspaces.set(byReason.getOrDefault(knownReason, 0L),
                    Attributes.of(TRAP_REASON_KEY, knownReason));
        }
        cycleTrappedWorkspaces.set(byReason.getOrDefault("other", 0L), Attributes.of(TRAP_REASON_KEY, "other"));
    }

    private Mono<Boolean> evictWorkspaceVersionCache(String workspaceId) {
        return workspaceVersionService.evictCache(workspaceId)
                .onErrorResume(throwable -> {
                    log.warn("Failed to evict workspace version cache, workspaceId='{}'", workspaceId, throwable);
                    return Mono.just(false);
                });
    }

}
