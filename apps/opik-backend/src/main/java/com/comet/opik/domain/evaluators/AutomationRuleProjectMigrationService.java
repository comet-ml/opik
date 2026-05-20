package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Project;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.infrastructure.AutomationRuleProjectMigrationConfig;
import com.comet.opik.infrastructure.MigrationConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
@Singleton
public class AutomationRuleProjectMigrationService {

    public static final String METRIC_NAMESPACE = "opik.migration.automation_rule_project";

    public static final AttributeKey<String> RESULT_KEY = stringKey("result");

    public static final Attributes RESULT_ERROR = Attributes.of(RESULT_KEY, "error");

    private static final Attributes RESULT_MIGRATED = Attributes.of(RESULT_KEY, "migrated");
    private static final Attributes RESULT_NO_MULTI_PROJECT_RULES = Attributes.of(RESULT_KEY,
            "no_multi_project_rules");
    private static final Attributes RESULT_ALL_SKIPPED = Attributes.of(RESULT_KEY, "all_projects_deleted");

    private static final Attributes SKIP_REASON_ALL_DELETED = Attributes.of(stringKey("reason"),
            "all_projects_deleted");
    private static final Attributes SKIP_REASON_PARTIAL_DELETED = Attributes.of(stringKey("reason"),
            "partial_projects_deleted");

    private static final String TRAPPED_REASON_ALL_PROJECTS_DELETED = "all_projects_deleted";

    private static final String CACHE_KEY_PATTERN = "*-%s-*";

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull CacheManager cacheManager;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AutomationRuleProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleTrappedWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter rulesSplit;
    private final LongCounter newRulesCreated;
    private final LongCounter rulesSkipped;

    @Inject
    public AutomationRuleProjectMigrationService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull ProjectService projectService,
            @NonNull WorkspacesService workspacesService,
            @NonNull CacheManager cacheManager,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("automationRuleProjectMigration") AutomationRuleProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.projectService = projectService;
        this.workspacesService = workspacesService;
        this.cacheManager = cacheManager;
        this.idGenerator = idGenerator;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with multi-project automation rules found per cycle")
                .ofLongs()
                .build();
        this.cycleTrappedWorkspaces = meter
                .gaugeBuilder("%s.cycle.trapped_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces trapped by automation rule migration")
                .ofLongs()
                .build();
        this.cycleEnvExcludedWorkspaces = meter
                .gaugeBuilder("%s.cycle.env_excluded_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces excluded via environment variable")
                .ofLongs()
                .build();
        this.workspaceDuration = meter
                .histogramBuilder("%s.workspace.duration".formatted(METRIC_NAMESPACE))
                .setDescription("Duration of a single workspace automation rule migration, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.rulesSplit = meter
                .counterBuilder("%s.rules.split".formatted(METRIC_NAMESPACE))
                .setDescription("Number of source rules that were split per cycle")
                .build();
        this.newRulesCreated = meter
                .counterBuilder("%s.new_rules.created".formatted(METRIC_NAMESPACE))
                .setDescription("Number of new rule rows inserted during split")
                .build();
        this.rulesSkipped = meter
                .counterBuilder("%s.rules.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Number of rules skipped during migration, tagged by reason")
                .build();
    }

    public void runMigrationCycle() {
        var skippedWorkspaceIds = workspacesService.findAutomationRuleMigrationSkippedWorkspaceIds();
        cycleTrappedWorkspaces.set(skippedWorkspaceIds.size());
        cycleEnvExcludedWorkspaces.set(migrationConfig.getExcludedWorkspaceIds().size());

        var excludedWorkspaceIds = Stream.concat(
                migrationConfig.getExcludedWorkspaceIds().stream(),
                skippedWorkspaceIds.stream())
                .collect(Collectors.toUnmodifiableSet());

        log.info("Starting automation rule project migration cycle, workspacesPerRun='{}', trappedWorkspaces='{}'",
                config.workspacesPerRun(), skippedWorkspaceIds.size());

        var placeholderIfEmpty = excludedWorkspaceIds.isEmpty()
                ? Set.of("__placeholder_never_matches__")
                : excludedWorkspaceIds;

        var eligibleWorkspaces = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findEligibleWorkspaces(placeholderIfEmpty, DemoData.AUTOMATION_RULES,
                                config.workspacesPerRun()));

        cycleEligibleWorkspaces.record(eligibleWorkspaces.size());

        if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
            log.info("No workspaces with multi-project automation rules found, consider disabling the job");
            return;
        }

        log.info("Found workspaces with multi-project automation rules, count='{}'", eligibleWorkspaces.size());

        for (var workspace : eligibleWorkspaces) {
            try {
                migrateWorkspace(workspace.workspaceId(), workspace.multiProjectRuleCount());
            } catch (Exception e) {
                log.error("Workspace automation rule migration failed, will retry next cycle, workspaceId='{}'",
                        workspace.workspaceId(), e);
                recordWorkspaceDuration(RESULT_ERROR, System.currentTimeMillis());
            }
        }
    }

    private void migrateWorkspace(String workspaceId, long multiProjectRuleCount) {
        log.info("Starting workspace automation rule migration, workspaceId='{}', multiProjectRuleCount='{}'",
                workspaceId, multiProjectRuleCount);
        var workspaceStartMillis = System.currentTimeMillis();

        var multiProjectRuleIds = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findMultiProjectRuleIds(workspaceId, DemoData.AUTOMATION_RULES));

        if (CollectionUtils.isEmpty(multiProjectRuleIds)) {
            log.info("No multi-project automation rules to migrate, workspaceId='{}'", workspaceId);
            recordWorkspaceDuration(RESULT_NO_MULTI_PROJECT_RULES, workspaceStartMillis);
            return;
        }

        int splitCount = 0;
        int newRuleCount = 0;
        int allDeletedCount = 0;

        for (var ruleId : multiProjectRuleIds) {
            var result = splitRule(workspaceId, ruleId);
            switch (result.outcome()) {
                case SPLIT -> {
                    splitCount++;
                    newRuleCount += result.newRulesCreated();
                }
                case ALL_PROJECTS_DELETED -> allDeletedCount++;
                case PARTIAL_DELETED -> {
                    splitCount++;
                    newRuleCount += result.newRulesCreated();
                }
                case NO_OP -> log.debug("Rule already single-project (race), ruleId='{}', workspaceId='{}'",
                        ruleId, workspaceId);
            }
        }

        rulesSplit.add(splitCount);
        newRulesCreated.add(newRuleCount);

        if (splitCount == 0 && allDeletedCount == multiProjectRuleIds.size()) {
            log.info("All multi-project rules have deleted projects, trapping workspace, workspaceId='{}'",
                    workspaceId);
            workspacesService.markAutomationRuleMigrationSkipped(workspaceId, TRAPPED_REASON_ALL_PROJECTS_DELETED);
            recordWorkspaceDuration(RESULT_ALL_SKIPPED, workspaceStartMillis);
            return;
        }

        evictAutomationRuleCache(workspaceId);

        log.info(
                "Workspace automation rule migration completed, workspaceId='{}', rulesSplit='{}', newRules='{}', allDeleted='{}'",
                workspaceId, splitCount, newRuleCount, allDeletedCount);
        recordWorkspaceDuration(RESULT_MIGRATED, workspaceStartMillis);
    }

    private SplitResult splitRule(String workspaceId, UUID ruleId) {
        var junctionProjectIds = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleProjectsDAO.class)
                        .findProjectIdsByRuleId(ruleId, workspaceId));

        if (junctionProjectIds.size() <= 1) {
            return new SplitResult(SplitOutcome.NO_OP, 0);
        }

        var existingProjects = projectService.findByIds(workspaceId, junctionProjectIds);
        var validProjectIds = existingProjects.stream()
                .map(Project::id)
                .collect(Collectors.toUnmodifiableSet());

        var deletedCount = junctionProjectIds.size() - validProjectIds.size();
        if (deletedCount > 0) {
            log.info("Rule has partially deleted projects, ruleId='{}', workspaceId='{}', valid='{}', deleted='{}'",
                    ruleId, workspaceId, validProjectIds.size(), deletedCount);
            rulesSkipped.add(deletedCount, SKIP_REASON_PARTIAL_DELETED);
        }

        if (validProjectIds.isEmpty()) {
            log.info("All projects deleted for rule, ruleId='{}', workspaceId='{}'", ruleId, workspaceId);
            rulesSkipped.add(1, SKIP_REASON_ALL_DELETED);
            return new SplitResult(SplitOutcome.ALL_PROJECTS_DELETED, 0);
        }

        var sortedProjectIds = new ArrayList<>(new TreeSet<>(validProjectIds));

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleMigrationDAO.class);

            dao.deleteJunctionByRuleId(ruleId, workspaceId);

            var firstProjectId = sortedProjectIds.get(0);
            dao.insertJunction(ruleId, firstProjectId, workspaceId);
            dao.clearLegacyProjectId(ruleId, workspaceId);

            int newRules = 0;
            for (int i = 1; i < sortedProjectIds.size(); i++) {
                var newRuleId = idGenerator.generateId();
                var projectId = sortedProjectIds.get(i);

                dao.copyBaseRule(newRuleId, ruleId, workspaceId);
                dao.copyEvaluator(newRuleId, ruleId);
                dao.insertJunction(newRuleId, projectId, workspaceId);
                dao.clearLegacyProjectId(newRuleId, workspaceId);
                newRules++;
            }

            log.debug("Split rule, ruleId='{}', workspaceId='{}', keptProject='{}', newRules='{}'",
                    ruleId, workspaceId, firstProjectId, newRules);

            return new SplitResult(
                    deletedCount > 0 ? SplitOutcome.PARTIAL_DELETED : SplitOutcome.SPLIT,
                    newRules);
        });
    }

    private void evictAutomationRuleCache(String workspaceId) {
        try {
            var key = CACHE_KEY_PATTERN.formatted(workspaceId);
            cacheManager.evictAsync(key, true);
            log.debug("Evicted automation rule cache for workspace, workspaceId='{}'", workspaceId);
        } catch (Exception e) {
            log.warn("Failed to evict automation rule cache, workspaceId='{}'", workspaceId, e);
        }
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }

    private enum SplitOutcome {
        SPLIT,
        PARTIAL_DELETED,
        ALL_PROJECTS_DELETED,
        NO_OP
    }

    private record SplitResult(SplitOutcome outcome, int newRulesCreated) {
    }
}
