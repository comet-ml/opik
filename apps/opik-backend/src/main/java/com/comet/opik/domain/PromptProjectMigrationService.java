package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.infrastructure.MigrationConfig;
import com.comet.opik.infrastructure.PromptProjectMigrationConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * D4 migration — assigns {@code project_id} to V1 (workspace-scoped) prompts. Sister to
 * {@link ExperimentProjectMigrationService}: same Quartz / Managed / scheduler-isolation shape
 * and the same four-bucket classification (certain / certain-deleted → Default Project /
 * no-inference → Default Project / ambiguous → skip). Differs only in the SQL — eligibility is
 * a pure MySQL scan of the {@code prompts} table, classification reads experiments in ClickHouse
 * to map each orphan prompt to a project via either the legacy {@code prompt_id} column or the
 * {@code prompt_versions} map.
 */
@Slf4j
@Singleton
public class PromptProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.prompt_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");
    public static final AttributeKey<String> REASON_KEY = stringKey("reason");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final String REASON_ALL_AMBIGUOUS = "all_ambiguous";

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ORPHAN_PROMPTS = Attributes.of(RESULT_KEY, "no_orphan_prompts");
    private static final Attributes RESULT_ALL_AMBIGUOUS = Attributes.of(RESULT_KEY, "all_ambiguous");

    private static final Attributes REASON_AMBIGUOUS = Attributes.of(REASON_KEY, "ambiguous");
    private static final Attributes REASON_DELETED_PROJECT = Attributes.of(REASON_KEY, "deleted_project");
    private static final Attributes REASON_NO_INFERENCE = Attributes.of(REASON_KEY, "no_inference");

    /**
     * Classifier output for a single orphan prompt. Maps directly to the {@code projectCount}
     * column from the ClickHouse classification query — {@code 0} → no-inference (route to
     * Default Project), {@code 1} → certain (assign inferred unless project deleted), anything
     * higher → ambiguous (skip and trap on cycle exit).
     */
    private enum Bucket {
        CERTAIN,
        NO_INFERENCE,
        AMBIGUOUS
    }

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull PromptProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter promptsSkipped;
    private final LongCounter promptsAssignedToDefault;
    private final LongHistogram batchSize;

    /**
     * Dedicated bounded-elastic scheduler isolating this job's blocking JDBC work and CPU
     * post-collect from {@link Schedulers#boundedElastic()} and the reactive client event loops.
     */
    private volatile Scheduler migrationScheduler;

    @Inject
    public PromptProjectMigrationService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull ExperimentDAO experimentDAO,
            @NonNull ProjectService projectService,
            @NonNull WorkspacesService workspacesService,
            @NonNull @Config("promptProjectMigration") PromptProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.experimentDAO = experimentDAO;
        this.projectService = projectService;
        this.workspacesService = workspacesService;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with orphan prompts found per cycle")
                .ofLongs()
                .build();
        this.cycleTrappedWorkspaces = meter
                .gaugeBuilder("%s.cycle.trapped_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces locally skipped because all their remaining prompts are ambiguous")
                .ofLongs()
                .build();
        this.cycleEnvExcludedWorkspaces = meter
                .gaugeBuilder("%s.cycle.env_excluded_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Number of workspaces excluded via MIGRATION_EXCLUDED_WORKSPACE_IDS")
                .ofLongs()
                .build();
        this.workspaceDuration = meter
                .histogramBuilder("%s.workspace.duration".formatted(METRIC_NAMESPACE))
                .setDescription("Duration of a single workspace migration, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.promptsSkipped = meter
                .counterBuilder("%s.prompts.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Prompts skipped during migration, tagged by reason")
                .build();
        this.promptsAssignedToDefault = meter
                .counterBuilder("%s.prompts.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription("Prompts re-routed to Default Project, tagged by reason")
                .build();
        this.batchSize = meter
                .histogramBuilder("%s.batch.size".formatted(METRIC_NAMESPACE))
                .setDescription("Size of each successful MySQL UPDATE batch")
                .ofLongs()
                .build();
    }

    @Override
    public void start() {
        if (migrationScheduler == null) {
            migrationScheduler = Schedulers.newBoundedElastic(
                    config.schedulerThreadCap(),
                    config.schedulerQueuedTaskCap(),
                    "prompt-project-migration-service",
                    (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized prompt project migration scheduler, threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    config.schedulerThreadCap(), config.schedulerQueuedTaskCap(), config.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Prompt project migration scheduler disposed");
        }
    }

    public Mono<Void> runMigrationCycle() {
        return Mono.fromCallable(() -> {
            var skippedWorkspaceIds = workspacesService.findPromptProjectMigrationSkippedWorkspaceIds();
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            cycleTrappedWorkspaces.set(skippedWorkspaceIds.size());
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting prompt project migration cycle, workspacesPerRun='{}', promptBatchSize='{}', trappedWorkspaces='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.promptBatchSize(), skippedWorkspaceIds.size(),
                    envExcludedWorkspaceIds.size());
            return Stream.concat(
                    envExcludedWorkspaceIds.stream(),
                    skippedWorkspaceIds.stream())
                    .collect(Collectors.toUnmodifiableSet());
        })
                .subscribeOn(migrationScheduler)
                .flatMapMany(excludedWorkspaceIds -> Mono
                        .fromCallable(() -> findEligibleWorkspaces(excludedWorkspaceIds))
                        .subscribeOn(migrationScheduler)
                        .flatMapMany(eligibleWorkspaces -> {
                            cycleEligibleWorkspaces.record(eligibleWorkspaces.size());
                            if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
                                log.info("No workspaces with orphan prompts found, consider disabling the job");
                                return Flux.empty();
                            }
                            log.info("Found workspaces with orphan prompts, count='{}'", eligibleWorkspaces.size());
                            return Flux.fromIterable(eligibleWorkspaces)
                                    .publishOn(migrationScheduler)
                                    .concatMap(workspace -> migrateWorkspace(
                                            workspace.workspaceId(),
                                            workspace.promptsCount(),
                                            config.promptBatchSize()));
                        }))
                .then();
    }

    private Mono<Boolean> migrateWorkspace(String workspaceId, long promptsCount, int batchSize) {
        log.info("Starting workspace migration, workspaceId='{}', promptsCount='{}'", workspaceId, promptsCount);
        var workspaceStartMillis = System.currentTimeMillis();
        return Mono
                .fromCallable(() -> findOrphanIds(workspaceId, batchSize))
                .subscribeOn(migrationScheduler)
                .flatMap(orphanIds -> {
                    if (CollectionUtils.isEmpty(orphanIds)) {
                        log.info("No orphan prompts to migrate, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_ORPHAN_PROMPTS, workspaceStartMillis);
                        return Mono.empty();
                    }
                    return classifyAndMigrate(workspaceId, Set.copyOf(orphanIds), batchSize, workspaceStartMillis);
                })
                .onErrorResume(throwable -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    log.error("Workspace migration failed, will retry next cycle, workspaceId='{}', duration='{}'",
                            workspaceId, duration, throwable);
                    recordWorkspaceDuration(RESULT_ERROR, workspaceStartMillis);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> classifyAndMigrate(
            String workspaceId,
            Set<UUID> orphanIds,
            int batchSize,
            long workspaceStartMillis) {
        return experimentDAO.computePromptProjectClassification(orphanIds)
                .contextWrite(ctx -> setRequestContext(ctx, SYSTEM_USER, workspaceId))
                .collectList()
                .publishOn(migrationScheduler)
                .flatMap(classifications -> applyClassifications(
                        workspaceId, orphanIds, classifications, batchSize, workspaceStartMillis));
    }

    private Mono<Boolean> applyClassifications(
            String workspaceId,
            Set<UUID> orphanIds,
            List<PromptProjectClassification> classifications,
            int batchSize,
            long workspaceStartMillis) {
        var classified = classifications.stream()
                .collect(Collectors.toUnmodifiableMap(PromptProjectClassification::promptId, c -> c));

        var byBucket = orphanIds.stream()
                .collect(Collectors.groupingBy(
                        promptId -> bucketOf(classified.get(promptId)),
                        Collectors.toUnmodifiableSet()));
        var ambiguous = byBucket.getOrDefault(Bucket.AMBIGUOUS, Set.of());
        var noInference = byBucket.getOrDefault(Bucket.NO_INFERENCE, Set.of());
        var certainByProject = byBucket.getOrDefault(Bucket.CERTAIN, Set.of()).stream()
                .collect(Collectors.groupingBy(
                        promptId -> classified.get(promptId).projectId(),
                        Collectors.toUnmodifiableSet()));

        var existingProjectIds = certainByProject.isEmpty()
                ? Set.<UUID>of()
                : projectService.findByIds(workspaceId, certainByProject.keySet()).stream()
                        .map(Project::id)
                        .collect(Collectors.toUnmodifiableSet());
        var certainAssignments = certainByProject.entrySet().stream()
                .filter(entry -> existingProjectIds.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        var deletedPromptIds = certainByProject.entrySet().stream()
                .filter(entry -> !existingProjectIds.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toUnmodifiableSet());

        logAndEmitMetrics(workspaceId, ambiguous, deletedPromptIds, noInference);

        var assignments = mergeAssignments(certainAssignments,
                getDefaultAssignments(workspaceId, deletedPromptIds, noInference));

        if (assignments.isEmpty()) {
            log.info("Only ambiguous prompts in workspace, trapping all_ambiguous, workspaceId='{}', count='{}'",
                    workspaceId, ambiguous.size());
            return markMigrationSkipped(workspaceId, workspaceStartMillis, Mono.empty());
        }

        return Flux.fromIterable(assignments.entrySet())
                .publishOn(migrationScheduler)
                .concatMap(entry -> batchUpdateProjectId(workspaceId, entry.getKey(), entry.getValue(), batchSize))
                .reduce(0L, Long::sum)
                .flatMap(totalUpdated -> {
                    var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                    if (!ambiguous.isEmpty()) {
                        log.info(
                                "Migration completed with remaining ambiguous prompts, marking workspace as trapped, workspaceId='{}', migrated='{}', ambiguous='{}', duration='{}'",
                                workspaceId, totalUpdated, ambiguous.size(), duration);
                        return markMigrationSkipped(workspaceId, workspaceStartMillis, Mono.just(false));
                    }
                    log.info(
                            "Workspace prompt migration completed, workspaceId='{}', migrated='{}', deletedProject='{}', noInference='{}', duration='{}'",
                            workspaceId, totalUpdated, deletedPromptIds.size(), noInference.size(), duration);
                    recordWorkspaceDuration(RESULT_MIGRATED, workspaceStartMillis);
                    return Mono.just(true);
                });
    }

    private Bucket bucketOf(PromptProjectClassification classification) {
        if (classification == null || classification.projectCount() == 0) {
            return Bucket.NO_INFERENCE;
        }
        if (classification.projectCount() == 1 && classification.projectId() != null) {
            return Bucket.CERTAIN;
        }
        return Bucket.AMBIGUOUS;
    }

    private void logAndEmitMetrics(
            String workspaceId,
            Set<UUID> ambiguous,
            Set<UUID> deletedPromptIds,
            Set<UUID> noInference) {
        if (!ambiguous.isEmpty()) {
            log.info("Skipping ambiguous prompts, workspaceId='{}', count='{}'", workspaceId, ambiguous.size());
            promptsSkipped.add(ambiguous.size(), REASON_AMBIGUOUS);
        }
        if (!deletedPromptIds.isEmpty()) {
            log.info("Assigning deleted-project prompts to Default Project, workspaceId='{}', count='{}'",
                    workspaceId, deletedPromptIds.size());
            promptsAssignedToDefault.add(deletedPromptIds.size(), REASON_DELETED_PROJECT);
        }
        if (!noInference.isEmpty()) {
            log.info("Assigning no-inference prompts to Default Project, workspaceId='{}', count='{}'",
                    workspaceId, noInference.size());
            promptsAssignedToDefault.add(noInference.size(), REASON_NO_INFERENCE);
        }
    }

    /**
     * Re-targets the certain-deleted and no-inference prompt sets to the workspace's Default
     * Project. {@link ProjectService#getOrCreate} is the same path trace ingestion takes, so a
     * missing Default Project is provisioned in-line rather than trapping the workspace. Returns
     * an empty map when neither bucket has rows so the caller can skip the merge cheaply.
     */
    private Map<UUID, Set<UUID>> getDefaultAssignments(
            String workspaceId,
            Set<UUID> deletedPromptIds,
            Set<UUID> noInference) {
        if (deletedPromptIds.isEmpty() && noInference.isEmpty()) {
            return Map.of();
        }
        var defaultProjectId = projectService
                .getOrCreate(workspaceId, ProjectService.DEFAULT_PROJECT, SYSTEM_USER)
                .id();
        var combined = Stream.concat(deletedPromptIds.stream(), noInference.stream())
                .collect(Collectors.toUnmodifiableSet());
        return Map.of(defaultProjectId, combined);
    }

    /**
     * Merges two project_id → prompt_id bucket maps. The Default Project key could coincide with
     * a {@code certainAssignments} key (an experiment legitimately points to the Default
     * Project), so the merge combines the two prompt sets rather than letting either side
     * shadow the other.
     */
    private Map<UUID, Set<UUID>> mergeAssignments(
            Map<UUID, Set<UUID>> certainAssignments,
            Map<UUID, Set<UUID>> defaultAssignments) {
        return Stream.concat(certainAssignments.entrySet().stream(), defaultAssignments.entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (certainPromptIds, defaultPromptIds) -> Stream
                                .concat(certainPromptIds.stream(), defaultPromptIds.stream())
                                .collect(Collectors.toUnmodifiableSet())));
    }

    private Mono<Boolean> markMigrationSkipped(
            String workspaceId,
            long workspaceStartMillis,
            Mono<Boolean> result) {
        return Mono.fromRunnable(() -> workspacesService.markPromptProjectMigrationSkipped(
                workspaceId, REASON_ALL_AMBIGUOUS))
                .subscribeOn(migrationScheduler)
                .doFinally(signalType -> recordWorkspaceDuration(RESULT_ALL_AMBIGUOUS, workspaceStartMillis))
                .then(result);
    }

    private Mono<Long> batchUpdateProjectId(
            String workspaceId,
            UUID projectId,
            Set<UUID> promptIds,
            int maxBatchSize) {
        return Flux.fromIterable(Lists.partition(List.copyOf(promptIds), maxBatchSize))
                .publishOn(migrationScheduler)
                .concatMap(batch -> Mono.fromCallable(() -> {
                    var batchSet = Set.copyOf(batch);
                    var updated = batchSetProjectId(workspaceId, batchSet, projectId);
                    batchSize.record(batch.size());
                    log.debug("Updated prompt batch, workspaceId='{}', projectId='{}', requested='{}', updated='{}'",
                            workspaceId, projectId, batch.size(), updated);
                    return updated;
                }).subscribeOn(migrationScheduler))
                .reduce(0L, Long::sum);
    }

    private List<EligiblePromptWorkspace> findEligibleWorkspaces(Set<String> excludedWorkspaceIds) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(PromptDAO.class).findEligiblePromptWorkspaces(
                        config.workspacesPerRun(), DemoData.PROMPTS, excludedWorkspaceIds));
    }

    private List<UUID> findOrphanIds(String workspaceId, int limit) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(PromptDAO.class).findOrphanPromptIds(workspaceId, DemoData.PROMPTS, limit));
    }

    private int batchSetProjectId(String workspaceId, Set<UUID> promptIds, UUID projectId) {
        return transactionTemplate.inTransaction(WRITE,
                handle -> handle.attach(PromptDAO.class).batchSetProjectId(workspaceId, promptIds, projectId,
                        SYSTEM_USER));
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }
}
