package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.workspaces.MigrationSkipReasonCount;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.domain.workspaces.WorkspacesService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private static final Attributes RESULT_ALL_SKIPPED_AMBIGUOUS = Attributes.of(RESULT_KEY, "all_skipped_ambiguous");

    // Skip-reason labels for the `datasets.skipped` counter. Only `ambiguous` is emitted today;
    // `deleted_project` is kept as a defensive constant in case a future code path needs it
    // (e.g. a dry-run tool that distinguishes "would have been deleted" from "would have been
    // assigned to default").
    @SuppressWarnings("unused")
    private static final Attributes SKIP_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"), "deleted_project");
    private static final Attributes SKIP_REASON_AMBIGUOUS = Attributes.of(stringKey("reason"), "ambiguous");

    // Reason labels for the `datasets.assigned_to_default` counter — diagnoses why a dataset
    // ended up in Default Project (their inferred project was deleted vs. no usable traces).
    private static final Attributes ASSIGNED_REASON_DELETED_PROJECT = Attributes.of(stringKey("reason"),
            "deleted_project");
    private static final Attributes ASSIGNED_REASON_NO_INFERENCE = Attributes.of(stringKey("reason"), "no_inference");

    private static final String TRAPPED_REASON_ALL_AMBIGUOUS = "all_ambiguous";
    // Kept as defensive constants — neither is emitted by the active code paths after the
    // policy alignment with D1 (Default Project is auto-created via getOrCreate; certain-deleted
    // datasets get reassigned to Default Project rather than trapping the workspace). Surfaced
    // through the gauge tag set so legacy rows persisted before the policy change still bucket.
    private static final String TRAPPED_REASON_DELETED_PROJECT = "deleted_project";
    private static final String TRAPPED_REASON_DEFAULT_PROJECT_MISSING = "default_project_missing";

    private static final AttributeKey<String> TRAP_REASON_KEY = stringKey("reason");
    // Known reasons surfaced by the trapped-workspaces gauge. Unknown strings (e.g. test-only or
    // legacy rows) fold to `other` to bound cardinality.
    private static final Set<String> KNOWN_TRAP_REASONS = Set.of(
            TRAPPED_REASON_DELETED_PROJECT, TRAPPED_REASON_ALL_AMBIGUOUS, TRAPPED_REASON_DEFAULT_PROJECT_MISSING);

    private static final String DEFAULT_PROJECT_NAME = ProjectService.DEFAULT_PROJECT;

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspaceVersionService workspaceVersionService;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull DatasetProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;
    private final @NonNull TransactionTemplate transactionTemplate;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter datasetsSkipped;
    private final LongCounter datasetsAssignedToDefault;
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
            @NonNull WorkspacesService workspacesService,
            @NonNull @Config("datasetProjectMigration") DatasetProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig,
            @NonNull TransactionTemplate transactionTemplate) {
        this.experimentDAO = experimentDAO;
        this.projectService = projectService;
        this.workspaceVersionService = workspaceVersionService;
        this.workspacesService = workspacesService;
        this.config = config;
        this.migrationConfig = migrationConfig;
        this.transactionTemplate = transactionTemplate;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with eligible datasets found per cycle")
                .ofLongs()
                .build();
        this.cycleTrappedWorkspaces = meter
                .gaugeBuilder("%s.cycle.trapped_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces locally skipped because of a persisted trap, tagged by reason "
                                + "(deleted_project / all_ambiguous / default_project_missing / other)")
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
                        "Duration of a single workspace migration, tagged by result (migrated / no_actionable / all_skipped_ambiguous / error)")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.datasetsSkipped = meter
                .counterBuilder("%s.datasets.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Total number of datasets skipped during migration, tagged by reason")
                .build();
        this.datasetsAssignedToDefault = meter
                .counterBuilder("%s.datasets.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan datasets (no inferable project) assigned to the workspace's Default Project. Diagnostic for the no-inference bucket size.")
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
            var skippedWorkspaceIds = workspacesService.findDatasetProjectMigrationSkippedWorkspaceIds();
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            recordTrappedWorkspacesByReason(workspacesService.countDatasetProjectMigrationSkippedByReason());
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting dataset project migration cycle, workspacesPerRun='{}', batchSize='{}', trappedWorkspaces='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.datasetBatchSize(), skippedWorkspaceIds.size(),
                    envExcludedWorkspaceIds.size());
            return Stream.concat(
                    envExcludedWorkspaceIds.stream(),
                    skippedWorkspaceIds.stream())
                    .collect(Collectors.toUnmodifiableSet());
        })
                .subscribeOn(migrationScheduler)
                .flatMap(excludedWorkspaceIds -> Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                        handle -> handle.attach(DatasetDAO.class).findEligibleDatasetMigrationWorkspaces(
                                DemoData.DATASETS, excludedWorkspaceIds, config.workspacesPerRun())))
                        .subscribeOn(migrationScheduler))
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
        return Mono.fromCallable(() -> {
            log.info("Starting workspace migration, workspaceId='{}', datasetsCount='{}'",
                    workspaceId, datasetsCount);
            // Full orphan set drives the no-inference bucket (orphans absent from the CH inference).
            return transactionTemplate.inTransaction(READ_ONLY,
                    handle -> handle.attach(DatasetDAO.class)
                            .findOrphanDatasetIdsInWorkspace(workspaceId, DemoData.DATASETS));
        })
                .subscribeOn(migrationScheduler)
                .flatMap(orphanIds -> {
                    if (CollectionUtils.isEmpty(orphanIds)) {
                        log.info("No orphan datasets remain, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_ACTIONABLE, workspaceStartMillis);
                        return Mono.just(false);
                    }
                    return experimentDAO.computeDatasetProjectMapping()
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

    /** Four-bucket classification: certain / certain-deleted / ambiguous / no-inference. */
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
        int ambiguousAcc = 0;
        for (var datasetId : orphanIds) {
            var inferred = inferenceByDataset.get(datasetId);
            if (inferred == null) {
                noInferenceIds.add(datasetId);
            } else if (inferred.distinctProjectCount() == 1) {
                certainCandidates.add(inferred);
            } else {
                ambiguousAcc++;
            }
        }
        final int ambiguousCount = ambiguousAcc;
        if (ambiguousCount > 0) {
            datasetsSkipped.add(ambiguousCount, SKIP_REASON_AMBIGUOUS);
            log.info("Skipping ambiguous datasets (multi-project), workspaceId='{}', count='{}'",
                    workspaceId, ambiguousCount);
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
                    return resolveDefaultProjectAndMigrate(
                            workspaceId, orphanIds, validatedCertain, noInferenceIds,
                            certainDeletedIds, ambiguousCount, workspaceStartMillis);
                });
    }

    /**
     * Reroutes certain-deleted and no-inference orphans to the workspace's Default Project.
     * Uses {@link ProjectService#getOrCreate} (the same path trace ingestion uses), so a missing
     * Default Project is provisioned in-line rather than trapping the workspace. The lookup is
     * skipped entirely when neither bucket has rows.
     */
    private Mono<Boolean> resolveDefaultProjectAndMigrate(
            String workspaceId,
            Set<UUID> orphanIds,
            List<DatasetProjectMapping> validatedCertain,
            List<UUID> noInferenceIds,
            List<UUID> certainDeletedIds,
            int ambiguousCount,
            long workspaceStartMillis) {
        int needsDefault = certainDeletedIds.size() + noInferenceIds.size();
        if (needsDefault == 0) {
            // Happy path: only validated-certain rows (or nothing actionable). Skip the
            // getOrCreate call entirely so we never auto-provision a Default Project for
            // workspaces that don't need one.
            return finalizeWorkspace(workspaceId, orphanIds.size(), new ArrayList<>(validatedCertain),
                    certainDeletedIds.size(), ambiguousCount, 0, workspaceStartMillis);
        }
        return Mono
                .fromCallable(() -> projectService.getOrCreate(workspaceId, DEFAULT_PROJECT_NAME, SYSTEM_USER).id())
                .subscribeOn(migrationScheduler)
                .flatMap(defaultProjectId -> {
                    var allMappings = new ArrayList<>(validatedCertain);
                    // distinctProjectCount is a query-side field; for the synthesized
                    // mappings it's unused downstream, so 1 is a safe filler.
                    for (var id : certainDeletedIds) {
                        allMappings.add(DatasetProjectMapping.builder()
                                .datasetId(id).projectId(defaultProjectId).distinctProjectCount(1L).build());
                    }
                    for (var id : noInferenceIds) {
                        allMappings.add(DatasetProjectMapping.builder()
                                .datasetId(id).projectId(defaultProjectId).distinctProjectCount(1L).build());
                    }
                    if (certainDeletedIds.size() > 0) {
                        datasetsAssignedToDefault.add(certainDeletedIds.size(), ASSIGNED_REASON_DELETED_PROJECT);
                        log.info("Assigning deleted-project datasets to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, certainDeletedIds.size());
                    }
                    if (noInferenceIds.size() > 0) {
                        datasetsAssignedToDefault.add(noInferenceIds.size(), ASSIGNED_REASON_NO_INFERENCE);
                        log.info("Assigning no-inference datasets to Default Project, workspaceId='{}', count='{}'",
                                workspaceId, noInferenceIds.size());
                    }
                    return finalizeWorkspace(workspaceId, orphanIds.size(), allMappings,
                            certainDeletedIds.size(), ambiguousCount, needsDefault, workspaceStartMillis);
                });
    }

    private Mono<Boolean> finalizeWorkspace(
            String workspaceId,
            int totalOrphans,
            List<DatasetProjectMapping> mappings,
            int certainDeleted,
            int ambiguousCount,
            int assignedToDefault,
            long workspaceStartMillis) {

        return migrateBatch(workspaceId, mappings)
                .flatMap(migrated -> migrated
                        ? evictWorkspaceVersionCache(workspaceId).thenReturn(true)
                        : Mono.just(false))
                .flatMap(__ -> resolveTrapReason(totalOrphans, mappings, certainDeleted, ambiguousCount)
                        .map(trap -> Mono.fromRunnable(
                                () -> workspacesService.markDatasetProjectMigrationSkipped(
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

    private Mono<Boolean> migrateBatch(String workspaceId, List<DatasetProjectMapping> mappings) {
        if (mappings.isEmpty()) {
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> {
            for (var batch : Lists.partition(mappings, config.datasetBatchSize())) {
                transactionTemplate.inTransaction(WRITE, handle -> {
                    handle.attach(DatasetDAO.class).batchSetProjectId(batch, workspaceId, SYSTEM_USER);
                    return null;
                });
                batchSize.record(batch.size());
                log.debug("Updated dataset batch, workspaceId='{}', count='{}'", workspaceId, batch.size());
            }
            return true;
        }).subscribeOn(migrationScheduler);
    }

    // Picks a trap reason if the workspace cannot make further progress next cycle, else empty.
    // After the D1 policy alignment, certain-deleted and no-inference both reroute to Default
    // Project (auto-provisioned), so the only remaining trap is `all_ambiguous` — workspaces
    // where every un-migrated orphan has trace links across multiple projects and the CLI/UI
    // tooling is required to disambiguate.
    private Optional<TrapDecision> resolveTrapReason(
            int totalOrphans, List<DatasetProjectMapping> mappings, int certainDeleted, int ambiguousCount) {
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
