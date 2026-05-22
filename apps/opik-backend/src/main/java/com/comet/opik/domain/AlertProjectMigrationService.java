package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.AlertProjectMigrationConfig;
import com.comet.opik.infrastructure.MigrationConfig;
import com.comet.opik.utils.JsonUtils;
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
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class AlertProjectMigrationService implements Managed {

    public static final String METRIC_NAMESPACE = "opik.migration.alert_project";
    public static final AttributeKey<String> RESULT_KEY = stringKey("result");
    public static final AttributeKey<String> REASON_KEY = stringKey("reason");
    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_ORPHAN_ALERTS = Attributes.of(RESULT_KEY, "no_orphan_alerts");

    private static final Attributes REASON_WORKSPACE_WIDE = Attributes.of(REASON_KEY, "workspace_wide");
    private static final Attributes REASON_ALL_PROJECTS_DELETED = Attributes.of(REASON_KEY, "all_projects_deleted");

    private final TransactionTemplate transactionTemplate;
    private final ProjectService projectService;
    private final IdGenerator idGenerator;
    private final AlertProjectMigrationConfig config;
    private final MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter alertsAssigned;
    private final LongCounter alertsSplit;
    private final LongCounter newAlertsCreated;
    private final LongCounter alertsAssignedToDefault;

    private volatile Scheduler migrationScheduler;

    @Inject
    public AlertProjectMigrationService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("alertProjectMigration") AlertProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.projectService = projectService;
        this.idGenerator = idGenerator;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with orphan alerts found per cycle")
                .ofLongs()
                .build();
        this.cycleEnvExcludedWorkspaces = meter
                .gaugeBuilder("%s.cycle.env_excluded_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces excluded via MIGRATION_EXCLUDED_WORKSPACE_IDS env var")
                .ofLongs()
                .build();
        this.workspaceDuration = meter
                .histogramBuilder("%s.workspace.duration".formatted(METRIC_NAMESPACE))
                .setDescription("Duration of a single workspace alert migration, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.alertsAssigned = meter
                .counterBuilder("%s.alerts.assigned".formatted(METRIC_NAMESPACE))
                .setDescription("Number of alerts directly assigned to a single valid project")
                .build();
        this.alertsSplit = meter
                .counterBuilder("%s.alerts.split".formatted(METRIC_NAMESPACE))
                .setDescription("Number of source alerts that were split into multiple project-scoped alerts")
                .build();
        this.newAlertsCreated = meter
                .counterBuilder("%s.new_alerts.created".formatted(METRIC_NAMESPACE))
                .setDescription("Number of new alert rows inserted as a result of splits")
                .build();
        this.alertsAssignedToDefault = meter
                .counterBuilder("%s.alerts.assigned_to_default".formatted(METRIC_NAMESPACE))
                .setDescription("Number of alerts assigned to the Default Project, tagged by reason")
                .build();
    }

    @Override
    public void start() {
        if (migrationScheduler == null) {
            migrationScheduler = Schedulers.newBoundedElastic(
                    config.schedulerThreadCap(),
                    config.schedulerQueuedTaskCap(),
                    "alert-project-migration-service",
                    (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                    true);
            log.info(
                    "Initialized alert project migration scheduler, threadCap='{}', queuedTaskCap='{}', threadTtl='{}'",
                    config.schedulerThreadCap(), config.schedulerQueuedTaskCap(), config.schedulerThreadTtl());
        }
    }

    @Override
    public void stop() {
        if (migrationScheduler != null && !migrationScheduler.isDisposed()) {
            migrationScheduler.dispose();
            log.info("Alert project migration scheduler disposed");
        }
    }

    public Mono<Void> runMigrationCycle() {
        return Mono.fromCallable(() -> {
            var envExcluded = migrationConfig.getExcludedWorkspaceIds();
            cycleEnvExcludedWorkspaces.set(envExcluded.size());
            log.info(
                    "Starting alert project migration cycle, workspacesPerRun='{}', envExcludedWorkspaces='{}'",
                    config.workspacesPerRun(), envExcluded.size());
            var eligible = fetchEligibleWorkspaces(envExcluded);
            cycleEligibleWorkspaces.record(eligible.size());
            if (eligible.isEmpty()) {
                log.info("No workspaces with orphan alerts found, consider disabling the job");
            } else {
                log.info("Found workspaces with orphan alerts, count='{}'", eligible.size());
            }
            return eligible;
        })
                .subscribeOn(migrationScheduler)
                .flatMapMany(Flux::fromIterable)
                .publishOn(migrationScheduler)
                .flatMap(workspace -> migrateWorkspace(workspace.workspaceId()), config.schedulerThreadCap())
                .then();
    }

    private Mono<Void> migrateWorkspace(String workspaceId) {
        var startMillis = System.currentTimeMillis();
        return Mono.<Void>fromRunnable(() -> doMigrateWorkspace(workspaceId, startMillis))
                .subscribeOn(migrationScheduler)
                .onErrorResume(throwable -> {
                    log.error("Workspace migration failed, workspaceId='{}'", workspaceId, throwable);
                    recordWorkspaceDuration(RESULT_ERROR, startMillis);
                    return Mono.empty();
                });
    }

    private void doMigrateWorkspace(String workspaceId, long startMillis) {
        List<Alert> orphanAlerts = fetchOrphanAlerts(workspaceId);

        if (orphanAlerts.isEmpty()) {
            log.info("No orphan alerts found, workspaceId='{}'", workspaceId);
            recordWorkspaceDuration(RESULT_NO_ORPHAN_ALERTS, startMillis);
            return;
        }

        log.info("Migrating workspace, workspaceId='{}', orphanAlertCount='{}'",
                workspaceId, orphanAlerts.size());

        // Default Project is resolved lazily and at most once per workspace cycle.
        AtomicReference<UUID> defaultProjectIdRef = new AtomicReference<>();
        Supplier<UUID> defaultProjectIdSupplier = () -> {
            UUID id = defaultProjectIdRef.get();
            if (id == null) {
                id = projectService.getOrCreate(workspaceId, ProjectService.DEFAULT_PROJECT, SYSTEM_USER).id();
                defaultProjectIdRef.set(id);
                log.info("Resolved Default Project, workspaceId='{}', projectId='{}'", workspaceId, id);
            }
            return id;
        };

        for (var alert : orphanAlerts) {
            migrateAlert(workspaceId, alert, defaultProjectIdSupplier);
        }

        recordWorkspaceDuration(RESULT_MIGRATED, startMillis);
        log.info("Workspace migration completed, workspaceId='{}', alertCount='{}'",
                workspaceId, orphanAlerts.size());
    }

    private void migrateAlert(String workspaceId, Alert alert, Supplier<UUID> defaultProjectIdSupplier) {
        var rawProjectIds = collectScopeProjectIds(alert);

        var validProjectIds = rawProjectIds.isEmpty()
                ? Set.<UUID>of()
                : projectService.findByIds(workspaceId, rawProjectIds).stream()
                        .map(Project::id)
                        .collect(Collectors.toUnmodifiableSet());

        if (!rawProjectIds.isEmpty() && validProjectIds.size() < rawProjectIds.size()) {
            log.info(
                    "Some project IDs no longer exist, workspaceId='{}', alertId='{}', raw='{}', valid='{}'",
                    workspaceId, alert.id(), rawProjectIds.size(), validProjectIds.size());
        }

        executeMigrationTransaction(workspaceId, alert, rawProjectIds, validProjectIds, defaultProjectIdSupplier);
    }

    private void executeAlertMigration(
            String workspaceId,
            Alert alert,
            Set<UUID> rawProjectIds,
            Set<UUID> validProjectIds,
            Supplier<UUID> defaultProjectIdSupplier,
            Handle handle) {
        var alertDAO = handle.attach(AlertDAO.class);
        boolean isWorkspaceWide = rawProjectIds.isEmpty();

        if (validProjectIds.isEmpty()) {
            var defaultProjectId = defaultProjectIdSupplier.get();
            alertDAO.updateAlertProjectId(alert.id(), defaultProjectId, workspaceId, SYSTEM_USER);
            if (!isWorkspaceWide) {
                alertDAO.deleteScopeProjectConfigs(alert.id());
            }
            alertsAssignedToDefault.add(1, isWorkspaceWide ? REASON_WORKSPACE_WIDE : REASON_ALL_PROJECTS_DELETED);
            log.info("Alert assigned to Default Project, workspaceId='{}', alertId='{}', reason='{}'",
                    workspaceId, alert.id(), isWorkspaceWide ? "workspace_wide" : "all_projects_deleted");

        } else {
            // One or more valid projects. Group triggers per project.
            // null key holds workspace-wide + all-deleted-project triggers → own Default Project alert.
            List<UUID> sortedProjectIds = validProjectIds.stream()
                    .sorted(Comparator.comparing(UUID::toString))
                    .collect(Collectors.toList());
            UUID firstProjectId = sortedProjectIds.get(0);

            Map<UUID, List<AlertTrigger>> triggersByProject = groupTriggersByProject(alert, validProjectIds);
            List<AlertTrigger> defaultTriggers = triggersByProject.getOrDefault(null, List.of());
            List<AlertTrigger> firstProjectTriggers = triggersByProject.getOrDefault(firstProjectId, List.of());

            // Original alert keeps only first-project triggers; everything else is removed.
            Set<UUID> keepIds = firstProjectTriggers.stream()
                    .map(AlertTrigger::id)
                    .collect(Collectors.toUnmodifiableSet());
            if (alert.triggers() != null) {
                Set<UUID> toDelete = alert.triggers().stream()
                        .map(AlertTrigger::id)
                        .filter(id -> !keepIds.contains(id))
                        .collect(Collectors.toUnmodifiableSet());
                if (!toDelete.isEmpty()) {
                    alertDAO.deleteTriggerConfigsByIds(toDelete);
                    alertDAO.deleteTriggersByIds(toDelete);
                }
            }
            alertDAO.updateAlertProjectId(alert.id(), firstProjectId, workspaceId, SYSTEM_USER);
            alertDAO.deleteScopeProjectConfigs(alert.id());

            // Workspace-wide + deleted-project triggers get their own new Default Project alert.
            if (!defaultTriggers.isEmpty()) {
                UUID defaultProjectId = defaultProjectIdSupplier.get();
                cloneAlertForProject(workspaceId, alert, defaultProjectId, defaultTriggers, handle);
                newAlertsCreated.add(1);
                alertsAssignedToDefault.add(1, REASON_WORKSPACE_WIDE);
                log.info(
                        "Default Project alert created for workspace-wide/deleted triggers, workspaceId='{}', alertId='{}'",
                        workspaceId, alert.id());
            }

            // Remaining valid projects get new alerts with only their own triggers.
            for (int i = 1; i < sortedProjectIds.size(); i++) {
                UUID projectId = sortedProjectIds.get(i);
                List<AlertTrigger> triggersForProject = triggersByProject.getOrDefault(projectId, List.of());
                cloneAlertForProject(workspaceId, alert, projectId, triggersForProject, handle);
                newAlertsCreated.add(1);
            }

            if (sortedProjectIds.size() > 1) {
                alertsSplit.add(1);
                log.info("Alert split, workspaceId='{}', alertId='{}', projectCount='{}'",
                        workspaceId, alert.id(), sortedProjectIds.size());
            } else {
                alertsAssigned.add(1);
                log.info("Alert assigned to single project, workspaceId='{}', alertId='{}', projectId='{}'",
                        workspaceId, alert.id(), firstProjectId);
            }
        }
    }

    private List<EligibleAlertWorkspace> fetchEligibleWorkspaces(Set<String> excluded) {
        var excludedList = new ArrayList<>(excluded);
        var demoNames = DemoData.ALERTS;
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AlertDAO.class).findEligibleAlertWorkspaces(
                        !excludedList.isEmpty(), excludedList,
                        !demoNames.isEmpty(), demoNames,
                        config.workspacesPerRun()));
    }

    private List<Alert> fetchOrphanAlerts(String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AlertDAO.class)
                        .findByWorkspaceId(workspaceId, true, config.alertBatchSize()));
    }

    private void executeMigrationTransaction(
            String workspaceId,
            Alert alert,
            Set<UUID> rawProjectIds,
            Set<UUID> validProjectIds,
            Supplier<UUID> defaultProjectIdSupplier) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            executeAlertMigration(workspaceId, alert, rawProjectIds, validProjectIds, defaultProjectIdSupplier,
                    handle);
            return null;
        });
    }

    private void cloneAlertForProject(String workspaceId, Alert source, UUID targetProjectId,
            List<AlertTrigger> triggers, Handle handle) {
        var newAlertId = idGenerator.generateId();
        var newWebhookId = idGenerator.generateId();

        var newWebhook = source.webhook().toBuilder()
                .id(newWebhookId)
                .name("Webhook for alert " + newAlertId)
                .createdAt(null)
                .lastUpdatedAt(null)
                .createdBy(SYSTEM_USER)
                .lastUpdatedBy(SYSTEM_USER)
                .build();
        handle.attach(WebhookDAO.class).save(workspaceId, newWebhook);

        var newAlert = source.toBuilder()
                .id(newAlertId)
                .projectId(targetProjectId)
                .createdAt(null)
                .lastUpdatedAt(null)
                .createdBy(SYSTEM_USER)
                .lastUpdatedBy(SYSTEM_USER)
                .build();
        handle.attach(AlertDAO.class).save(workspaceId, newAlert, newWebhookId);

        if (CollectionUtils.isNotEmpty(triggers)) {
            var newTriggers = triggers.stream()
                    .map(trigger -> cloneTrigger(trigger, newAlertId))
                    .toList();
            handle.attach(AlertTriggerDAO.class).saveBatch(newTriggers);

            var newConfigs = newTriggers.stream()
                    .filter(t -> CollectionUtils.isNotEmpty(t.triggerConfigs()))
                    .flatMap(t -> t.triggerConfigs().stream())
                    .toList();
            if (!newConfigs.isEmpty()) {
                handle.attach(AlertTriggerConfigDAO.class).saveBatch(newConfigs);
            }
        }
    }

    private Map<UUID, List<AlertTrigger>> groupTriggersByProject(Alert alert, Set<UUID> validProjectIds) {
        Map<UUID, List<AlertTrigger>> groups = new HashMap<>();
        if (alert.triggers() == null) {
            return groups;
        }
        for (var trigger : alert.triggers()) {
            var validForTrigger = extractTriggerProjectIds(trigger).stream()
                    .filter(validProjectIds::contains)
                    .collect(Collectors.toUnmodifiableSet());
            if (validForTrigger.isEmpty()) {
                groups.computeIfAbsent(null, k -> new ArrayList<>()).add(trigger);
            } else {
                for (var projectId : validForTrigger) {
                    groups.computeIfAbsent(projectId, k -> new ArrayList<>()).add(trigger);
                }
            }
        }
        return groups;
    }

    private Set<UUID> extractTriggerProjectIds(AlertTrigger trigger) {
        if (trigger.triggerConfigs() == null) {
            return Set.of();
        }
        return trigger.triggerConfigs().stream()
                .filter(c -> c.type() == AlertTriggerConfigType.SCOPE_PROJECT)
                .flatMap(c -> {
                    var projectIdsStr = c.configValue() != null
                            ? c.configValue().get(AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY)
                            : null;
                    if (StringUtils.isBlank(projectIdsStr)) {
                        return Stream.empty();
                    }
                    try {
                        return JsonUtils.<Set<UUID>>readCollectionValue(projectIdsStr, Set.class, UUID.class).stream();
                    } catch (Exception e) {
                        log.warn("Skipping malformed '{}' value for trigger '{}', treating as workspace-wide",
                                AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY, trigger.id(), e);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private AlertTrigger cloneTrigger(AlertTrigger source, UUID newAlertId) {
        var newTriggerId = idGenerator.generateId();
        List<AlertTriggerConfig> nonScopeConfigs = source.triggerConfigs() == null
                ? null
                : source.triggerConfigs().stream()
                        .filter(c -> c.type() != AlertTriggerConfigType.SCOPE_PROJECT)
                        .map(c -> c.toBuilder()
                                .id(idGenerator.generateId())
                                .alertTriggerId(newTriggerId)
                                .createdAt(null)
                                .lastUpdatedAt(null)
                                .createdBy(SYSTEM_USER)
                                .lastUpdatedBy(SYSTEM_USER)
                                .build())
                        .toList();
        return source.toBuilder()
                .id(newTriggerId)
                .alertId(newAlertId)
                .createdAt(null)
                .createdBy(SYSTEM_USER)
                .triggerConfigs(nonScopeConfigs)
                .build();
    }

    private Set<UUID> collectScopeProjectIds(Alert alert) {
        if (alert.triggers() == null) {
            return Set.of();
        }
        return alert.triggers().stream()
                .flatMap(t -> extractTriggerProjectIds(t).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }
}
