package com.comet.opik.domain;

import com.comet.opik.api.Project;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * D4 migration — assigns {@code project_id} to V1 (workspace-scoped) prompts. Sister to
 * {@link ExperimentProjectMigrationService} and {@link DatasetProjectMigrationService}: same
 * Quartz / Managed / scheduler-isolation shape and the same dominant-project classification
 * (certain / certain-deleted → Default Project / no-inference → Default Project). Differs only in
 * the SQL — eligibility is a pure MySQL scan of the {@code prompts} table, classification reads
 * experiments in ClickHouse to map each orphan prompt to a project via either the legacy
 * {@code prompt_id} column or the {@code prompt_versions} map, and ties across multiple projects
 * are resolved by {@code (count DESC, last_activity DESC, project_id ASC)}.
 */
@Slf4j
@Singleton
public class PromptProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.prompt_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");
    public static final AttributeKey<String> REASON_KEY = stringKey("reason");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ORPHAN_PROMPTS = Attributes.of(RESULT_KEY, "no_orphan_prompts");

    private static final Attributes REASON_DELETED_PROJECT = Attributes.of(REASON_KEY, "deleted_project");
    private static final Attributes REASON_NO_INFERENCE = Attributes.of(REASON_KEY, "no_inference");

    /** Tags {@code prompts.assigned_to_dominant_project} by the source distinct project count. */
    private static final AttributeKey<String> DISTINCT_PROJECT_COUNT_KEY = stringKey("distinct_project_count");

    /** Cap on the {@link #DISTINCT_PROJECT_COUNT_KEY} label value to bound metric cardinality. */
    private static final int DISTINCT_PROJECT_COUNT_MAX = 50;

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull PromptProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter promptsAssignedToDefault;
    private final LongCounter promptsAssignedToDominantProject;
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
            @NonNull @Config("promptProjectMigration") PromptProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.experimentDAO = experimentDAO;
        this.projectService = projectService;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with orphan prompts found per cycle")
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
        this.promptsAssignedToDefault = meter
                .counterBuilder("%s.prompts.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription("Prompts re-routed to Default Project, tagged by reason")
                .build();
        this.promptsAssignedToDominantProject = meter
                .counterBuilder("%s.prompts.assigned_to_dominant_project".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Total number of orphan prompts that referenced multiple projects and were assigned to the dominant one (most referencing experiments). Tagged by distinct_project_count for the multi-project distribution.")
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
            var envExcludedWorkspaceIds = migrationConfig.getExcludedWorkspaceIds();
            cycleEnvExcludedWorkspaces.set(envExcludedWorkspaceIds.size());
            log.info(
                    "Starting prompt project migration cycle, workspacesPerRun='{}', promptBatchSize='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), config.promptBatchSize(), envExcludedWorkspaceIds.size());
            return envExcludedWorkspaceIds;
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

    /**
     * Splits the orphans into three groups: certain (the inferred project still exists, including
     * the dominant project picked by the query for multi-project prompts), certain-deleted (the
     * inferred project no longer exists in MySQL), and no-inference (no usable referencing
     * experiment). Multi-project prompts flow through the certain path because the SQL already
     * picked their dominant project; the dominant-assignment metric and log are emitted here, and
     * the other two groups are routed to the workspace's Default Project.
     */
    private Mono<Boolean> applyClassifications(
            String workspaceId,
            Set<UUID> orphanIds,
            List<PromptProjectClassification> classifications,
            int batchSize,
            long workspaceStartMillis) {
        var classifiedByPrompt = classifications.stream()
                .collect(Collectors.toUnmodifiableMap(PromptProjectClassification::promptId, Function.identity()));

        var certainCandidates = new ArrayList<PromptProjectClassification>();
        var noInferenceIds = new ArrayList<UUID>();
        for (var promptId : orphanIds) {
            var inferred = classifiedByPrompt.get(promptId);
            if (inferred == null) {
                noInferenceIds.add(promptId);
            } else {
                certainCandidates.add(inferred);
            }
        }

        var inferredProjectIds = certainCandidates.stream()
                .map(PromptProjectClassification::projectId)
                .collect(Collectors.toUnmodifiableSet());
        return Mono.fromCallable(() -> inferredProjectIds.isEmpty()
                ? Set.<UUID>of()
                : projectService.findByIds(workspaceId, inferredProjectIds).stream()
                        .map(Project::id)
                        .collect(Collectors.toUnmodifiableSet()))
                .subscribeOn(migrationScheduler)
                .flatMap(validProjectIds -> {
                    var certain = certainCandidates.stream()
                            .filter(classification -> validProjectIds.contains(classification.projectId()))
                            .toList();
                    var certainDeletedIds = certainCandidates.stream()
                            .filter(classification -> !validProjectIds.contains(classification.projectId()))
                            .map(PromptProjectClassification::promptId)
                            .toList();

                    recordDominantAssignments(workspaceId, certain);
                    logAndEmitDefaultProjectMetrics(workspaceId, certainDeletedIds, noInferenceIds);

                    var assignments = buildAssignments(workspaceId, certain, certainDeletedIds, noInferenceIds);
                    if (assignments.isEmpty()) {
                        log.info("No prompts to migrate after classification, workspaceId='{}'", workspaceId);
                        recordWorkspaceDuration(RESULT_NO_ORPHAN_PROMPTS, workspaceStartMillis);
                        return Mono.just(false);
                    }
                    return Flux.fromIterable(assignments.entrySet())
                            .publishOn(migrationScheduler)
                            .concatMap(entry -> batchUpdateProjectId(
                                    workspaceId, entry.getKey(), entry.getValue(), batchSize))
                            .reduce(0L, Long::sum)
                            .doOnSuccess(totalUpdated -> {
                                var duration = Duration.ofMillis(System.currentTimeMillis() - workspaceStartMillis);
                                log.info(
                                        "Workspace prompt migration completed, workspaceId='{}', migrated='{}', certain='{}', certainDeleted='{}', noInference='{}', duration='{}'",
                                        workspaceId, totalUpdated, certain.size(), certainDeletedIds.size(),
                                        noInferenceIds.size(), duration);
                                recordWorkspaceDuration(RESULT_MIGRATED, workspaceStartMillis);
                            })
                            .thenReturn(true);
                });
    }

    /**
     * Bumps {@code prompts.assigned_to_dominant_project} and logs the chosen project plus its
     * per-project experiment breakdown for each validated multi-project prompt. Single-project
     * rows are skipped — no dominant decision was made.
     */
    private void recordDominantAssignments(String workspaceId, List<PromptProjectClassification> certain) {
        for (var classification : certain) {
            if (classification.distinctProjectCount() <= 1) {
                continue;
            }
            long boundedCount = Math.min(classification.distinctProjectCount(), DISTINCT_PROJECT_COUNT_MAX);
            promptsAssignedToDominantProject.add(1,
                    Attributes.of(DISTINCT_PROJECT_COUNT_KEY, Long.toString(boundedCount)));
            log.info(
                    "Assigning dominant project to prompt, workspaceId='{}', promptId='{}', chosenProjectId='{}', distinctProjectCount='{}', projectBreakdown='{}'",
                    workspaceId, classification.promptId(), classification.projectId(),
                    classification.distinctProjectCount(), classification.projectBreakdown());
        }
    }

    private void logAndEmitDefaultProjectMetrics(
            String workspaceId,
            List<UUID> certainDeletedIds,
            List<UUID> noInferenceIds) {
        if (!certainDeletedIds.isEmpty()) {
            log.info("Assigning deleted-project prompts to Default Project, workspaceId='{}', count='{}'",
                    workspaceId, certainDeletedIds.size());
            promptsAssignedToDefault.add(certainDeletedIds.size(), REASON_DELETED_PROJECT);
        }
        if (!noInferenceIds.isEmpty()) {
            log.info("Assigning no-inference prompts to Default Project, workspaceId='{}', count='{}'",
                    workspaceId, noInferenceIds.size());
            promptsAssignedToDefault.add(noInferenceIds.size(), REASON_NO_INFERENCE);
        }
    }

    /**
     * Groups every actionable prompt by its destination {@code project_id} so the caller can
     * issue one MySQL batch UPDATE per project. The Default Project is provisioned only when at
     * least one prompt actually needs it. When an experiment legitimately references the Default
     * Project, the certain-by-project key collides with the synthesized default-project key, and
     * the merge function unions both prompt sets rather than letting either side shadow the
     * other.
     */
    private Map<UUID, Set<UUID>> buildAssignments(
            String workspaceId,
            List<PromptProjectClassification> certain,
            List<UUID> certainDeletedIds,
            List<UUID> noInferenceIds) {
        var certainByProject = certain.stream()
                .collect(Collectors.groupingBy(
                        PromptProjectClassification::projectId,
                        Collectors.mapping(PromptProjectClassification::promptId, Collectors.toUnmodifiableSet())));
        if (certainDeletedIds.isEmpty() && noInferenceIds.isEmpty()) {
            return certainByProject;
        }
        var defaultProjectId = projectService
                .getOrCreate(workspaceId, ProjectService.DEFAULT_PROJECT, SYSTEM_USER)
                .id();
        var defaultPromptIds = Stream.concat(certainDeletedIds.stream(), noInferenceIds.stream())
                .collect(Collectors.toUnmodifiableSet());
        return Stream.concat(
                certainByProject.entrySet().stream(),
                Stream.of(Map.entry(defaultProjectId, defaultPromptIds)))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (certainPromptIds, defaultPromptIdsForKey) -> Stream
                                .concat(certainPromptIds.stream(), defaultPromptIdsForKey.stream())
                                .collect(Collectors.toUnmodifiableSet())));
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
