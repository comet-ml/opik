package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.infrastructure.DatasetProjectMigrationConfig;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class DatasetProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.dataset_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ACTIONABLE = Attributes.of(RESULT_KEY, "no_actionable");

    // Reason labels for the `datasets.assigned_to_default` counter — diagnoses why a dataset
    // ended up in Default Project (their inferred project was deleted vs. no usable traces).
    private static final Attributes ASSIGNED_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"),
            "deleted_project");
    private static final Attributes ASSIGNED_REASON_NO_INFERENCE = Attributes.of(stringKey("reason"), "no_inference");

    /**
     * Label on the {@code datasets.assigned_to_dominant_project} counter that breaks the count-down
     * by the number of projects a dataset's experiments referenced.
     */
    private static final AttributeKey<String> DISTINCT_PROJECT_COUNT_KEY = stringKey("distinct_project_count");

    /**
     * Upper bound applied to the {@link #DISTINCT_PROJECT_COUNT_KEY} label value so that an
     * unexpectedly large project count cannot create an unbounded number of metric series. Counts
     * at or above this value share a single label.
     */
    private static final int DISTINCT_PROJECT_COUNT_MAX = 50;

    private static final String DEFAULT_PROJECT_NAME = ProjectService.DEFAULT_PROJECT;

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull DatasetProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;
    private final @NonNull TransactionTemplate transactionTemplate;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter datasetsAssignedToDefault;
    private final LongCounter datasetsAssignedToDominantProject;
    private final LongHistogram batchSize;

    /**
     * Dedicated bounded-elastic scheduler isolating the migration's blocking JDBC work and
     * its post-collect CPU-heavy lambdas from the shared {@link Schedulers#boundedElastic()}
     * and from the reactive client (R2DBC/Redisson) event loops. Sized for the sequential
     * concatMap flow; daemon threads so JVM shutdown is never blocked by the migration pool.
     */
    private volatile Scheduler migrationScheduler;

    @Inject
    public DatasetProjectMigrationService(
            @NonNull ExperimentDAO experimentDAO,
            @NonNull ProjectService projectService,
            @NonNull WorkspaceVersionService workspaceVersionService,
            @NonNull @Config("datasetProjectMigration") DatasetProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig,
            @NonNull TransactionTemplate transactionTemplate) {
        this.experimentDAO = experimentDAO;
        this.projectService = projectService;
        this.workspaceVersionService = workspaceVersionService;
        this.config = config;
        this.migrationConfig = migrationConfig;
        this.transactionTemplate = transactionTemplate;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with eligible datasets found per cycle")
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
                        "Duration of a single workspace migration, tagged by result (migrated / no_actionable / error)")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.datasetsAssignedToDefault = meter
                .counterBuilder("%s.datasets.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan datasets (no inferable project) assigned to the workspace's Default Project. Diagnostic for the no-inference bucket size.")
                .build();
        this.datasetsAssignedToDominantProject = meter
                .counterBuilder("%s.datasets.assigned_to_dominant_project".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan datasets that referenced multiple projects and were assigned to the dominant one (most referencing experiments). Tagged by distinct_project_count for the multi-project distribution.")
                .build();
        this.batchSize = meter
                .histogramBuilder("%s.batch.size".formatted(METRIC_NAMESPACE))
                .setDescription("Size of each successful MySQL batch update")
                .ofLongs()
                .build();
    }

    @Override
    public void start() {
        if (migrationScheduler == null) {
            migrationScheduler = Schedulers.newBoundedElastic(
                    config.schedulerThreadCap(),
                    config.schedulerQueuedTaskCap(),
                    "dataset-project-migration-service",
                    (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized dataset project migration scheduler, threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    config.schedulerThreadCap(), config.schedulerQueuedTaskCap(), config.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Dataset project migration scheduler disposed");
        }
    }

    public Mono<Void> runMigrationCycle() {
        return Mono.fromCallable(() -> {
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting dataset project migration cycle, workspacesPerRun='{}', batchSize='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.datasetBatchSize(), envExcludedWorkspaceIds.size());
            return envExcludedWorkspaceIds;
        })
                .subscribeOn(migrationScheduler)
                .flatMap(this::findEligibleWorkspaces)
                .flatMapMany(eligibleWorkspaces -> {
                    cycleEligibleWorkspaces.record(eligibleWorkspaces.size());
                    if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
                        log.info("No workspaces with eligible datasets found, consider disabling the job");
                        return Flux.empty();
                    }
                    log.info("Found workspaces with eligible datasets, count='{}'", eligibleWorkspaces.size());
                    return Flux.fromIterable(eligibleWorkspaces)
                            .concatMap(workspace -> migrateWorkspace(
                                    workspace.workspaceId(),
                                    workspace.datasetsCount()));
                })
                .then();
    }

    private Mono<Boolean> migrateWorkspace(String workspaceId, long datasetsCount) {
        var workspaceStartMillis = System.currentTimeMillis();
        log.info("Starting workspace migration, workspaceId='{}', datasetsCount='{}'", workspaceId, datasetsCount);
        // Full orphan set drives the no-inference bucket (orphans absent from the CH inference).
        return findOrphanDatasetIds(workspaceId)
                .flatMap(orphanIds -> {
                    if (CollectionUtils.isEmpty(orphanIds)) {
                        log.info("No orphan datasets remain, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_ACTIONABLE, workspaceStartMillis);
                        return Mono.just(false);
                    }
                    return experimentDAO.computeDatasetProjectMapping(orphanIds)
                            .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                            .collectList()
                            .publishOn(migrationScheduler)
                            .flatMap(inferenceResults -> classifyAndMigrate(
                                    workspaceId, orphanIds, inferenceResults, workspaceStartMillis));
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
     * Classifies each orphan dataset into one of three groups: certain (a single referencing
     * project, including the dominant project already chosen for multi-project datasets),
     * certain-deleted (the inferred project no longer exists), and no-inference (no usable
     * referencing experiment). Multi-project datasets are treated as certain because the query has
     * already chosen their dominant project; this method then confirms the project still exists,
     * records the dominant-assignment metric and log, and routes the other two groups to the
     * Default Project.
     */
    private Mono<Boolean> classifyAndMigrate(
            String workspaceId,
            Set<UUID> orphanIds,
            List<DatasetProjectMapping> inferenceResults,
            long workspaceStartMillis) {

        var inferenceByDataset = inferenceResults.stream()
                .collect(Collectors.toMap(DatasetProjectMapping::datasetId, Function.identity(),
                        (a, b) -> a));

        var certainCandidates = new ArrayList<DatasetProjectMapping>();
        var noInferenceIds = new ArrayList<UUID>();
        for (var datasetId : orphanIds) {
            var inferred = inferenceByDataset.get(datasetId);
            if (inferred == null) {
                noInferenceIds.add(datasetId);
            } else {
                certainCandidates.add(inferred);
            }
        }

        return Mono.fromCallable(() -> {
            if (certainCandidates.isEmpty()) {
                return List.<DatasetProjectMapping>of();
            }
            var inferredProjectIds = certainCandidates.stream()
                    .map(DatasetProjectMapping::projectId)
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
                    var certainDeletedIds = certainCandidates.stream()
                            .filter(c -> !validatedCertain.contains(c))
                            .map(DatasetProjectMapping::datasetId)
                            .toList();
                    recordDominantAssignments(workspaceId, validatedCertain);
                    return resolveDefaultProjectAndMigrate(
                            workspaceId, orphanIds, validatedCertain, noInferenceIds,
                            certainDeletedIds, workspaceStartMillis);
                });
    }

    /**
     * For each validated multi-project dataset, increments
     * {@code datasets.assigned_to_dominant_project} (labeled by distinct_project_count, capped at
     * {@link #DISTINCT_PROJECT_COUNT_MAX}) and logs the chosen project together with the per-project
     * counts that determined it.
     */
    private void recordDominantAssignments(String workspaceId, List<DatasetProjectMapping> validatedCertain) {
        for (var mapping : validatedCertain) {
            if (mapping.distinctProjectCount() <= 1) {
                continue;
            }
            long boundedCount = Math.min(mapping.distinctProjectCount(), DISTINCT_PROJECT_COUNT_MAX);
            datasetsAssignedToDominantProject.add(1,
                    Attributes.of(DISTINCT_PROJECT_COUNT_KEY, Long.toString(boundedCount)));
            log.info(
                    "Assigning dominant project to dataset, workspaceId='{}', datasetId='{}', chosenProjectId='{}', distinctProjectCount='{}', projectBreakdown='{}'",
                    workspaceId, mapping.datasetId(), mapping.projectId(), mapping.distinctProjectCount(),
                    mapping.projectBreakdown());
        }
    }

    /**
     * Assigns the certain-deleted and no-inference datasets to the workspace's Default Project,
     * creating it if absent via {@link ProjectService#getOrCreate} (the same call trace ingestion
     * uses). The lookup is skipped when neither group has any datasets.
     */
    private Mono<Boolean> resolveDefaultProjectAndMigrate(
            String workspaceId,
            Set<UUID> orphanIds,
            List<DatasetProjectMapping> validatedCertain,
            List<UUID> noInferenceIds,
            List<UUID> certainDeletedIds,
            long workspaceStartMillis) {
        int needsDefault = certainDeletedIds.size() + noInferenceIds.size();
        if (needsDefault == 0) {
            // Happy path: only validated-certain rows (or nothing actionable). Skip the
            // getOrCreate call entirely so we never auto-provision a Default Project for
            // workspaces that don't need one.
            return finalizeWorkspace(workspaceId, orphanIds.size(), new ArrayList<>(validatedCertain),
                    certainDeletedIds.size(), 0, workspaceStartMillis);
        }
        return Mono
                .fromCallable(() -> projectService.getOrCreate(workspaceId, DEFAULT_PROJECT_NAME, SYSTEM_USER).id())
                .subscribeOn(migrationScheduler)
                .flatMap(defaultProjectId -> {
                    var allMappings = new ArrayList<>(validatedCertain);
                    // distinctProjectCount and projectBreakdown are query-side fields; for the
                    // synthesized Default Project mappings they're unused downstream, so 1 and an
                    // empty breakdown are safe fillers.
                    for (var id : certainDeletedIds) {
                        allMappings.add(DatasetProjectMapping.builder()
                                .datasetId(id).projectId(defaultProjectId).distinctProjectCount(1L)
                                .projectBreakdown("").build());
                    }
                    for (var id : noInferenceIds) {
                        allMappings.add(DatasetProjectMapping.builder()
                                .datasetId(id).projectId(defaultProjectId).distinctProjectCount(1L)
                                .projectBreakdown("").build());
                    }
                    if (!certainDeletedIds.isEmpty()) {
                        datasetsAssignedToDefault.add(certainDeletedIds.size(), ASSIGNED_REASON_DELETED_PROJECT);
                        log.info("Assigning deleted-project datasets to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, certainDeletedIds.size());
                    }
                    if (!noInferenceIds.isEmpty()) {
                        datasetsAssignedToDefault.add(noInferenceIds.size(), ASSIGNED_REASON_NO_INFERENCE);
                        log.info("Assigning no-inference datasets to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, noInferenceIds.size());
                    }
                    return finalizeWorkspace(workspaceId, orphanIds.size(), allMappings,
                            certainDeletedIds.size(), needsDefault, workspaceStartMillis);
                });
    }

    private Mono<Boolean> finalizeWorkspace(
            String workspaceId,
            int totalOrphans,
            List<DatasetProjectMapping> mappings,
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

    private Mono<Boolean> migrateBatch(String workspaceId, List<DatasetProjectMapping> mappings) {
        if (mappings.isEmpty()) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> {
            for (var batch : Lists.partition(mappings, config.datasetBatchSize())) {
                writeBatch(workspaceId, batch);
                batchSize.record(batch.size());
                log.debug("Updated dataset batch, workspaceId='{}', count='{}'", workspaceId, batch.size());
            }
            return true;
        }).subscribeOn(migrationScheduler);
    }

    private Mono<List<EligibleDatasetWorkspace>> findEligibleWorkspaces(Set<String> excludedWorkspaceIds) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(DatasetDAO.class).findEligibleDatasetMigrationWorkspaces(
                        DemoData.DATASETS, excludedWorkspaceIds, config.workspacesPerRun())))
                .subscribeOn(migrationScheduler);
    }

    private Mono<Set<UUID>> findOrphanDatasetIds(String workspaceId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(DatasetDAO.class)
                        .findOrphanDatasetIdsInWorkspace(workspaceId, DemoData.DATASETS)))
                .subscribeOn(migrationScheduler);
    }

    private void writeBatch(String workspaceId, List<DatasetProjectMapping> batch) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(DatasetDAO.class).batchSetProjectId(batch, workspaceId, SYSTEM_USER);
            return null;
        });
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
