package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Project;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AutomationRuleProjectMigrationConfig;
import com.comet.opik.infrastructure.MigrationConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
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

    private static final Attributes SKIP_REASON_PARTIAL_DELETED = Attributes.of(stringKey("reason"),
            "partial_projects_deleted");
    private static final Attributes MOVED_TO_DEFAULT_ATTR = Attributes.of(stringKey("reason"),
            "moved_to_default_project");

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;
    private final @NonNull AutomationRuleEvaluatorService evaluatorService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AutomationRuleProjectMigrationConfig config;
    private final @NonNull MigrationConfig migrationConfig;

    private final LongHistogram cycleEligibleWorkspaces;
    private final LongGauge cycleEnvExcludedWorkspaces;
    private final LongHistogram workspaceDuration;
    private final LongCounter rulesSplit;
    private final LongCounter newRulesCreated;
    private final LongCounter rulesMovedToDefault;
    private final LongCounter rulesSkipped;

    @Inject
    public AutomationRuleProjectMigrationService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService evaluatorService,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("automationRuleProjectMigration") AutomationRuleProjectMigrationConfig config,
            @NonNull @Config("migration") MigrationConfig migrationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.projectService = projectService;
        this.evaluatorService = evaluatorService;
        this.idGenerator = idGenerator;
        this.config = config;
        this.migrationConfig = migrationConfig;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleEligibleWorkspaces = meter
                .histogramBuilder("%s.cycle.eligible_workspaces".formatted(METRIC_NAMESPACE))
                .setDescription("Number of workspaces with multi-project automation rules found per cycle")
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
        this.rulesMovedToDefault = meter
                .counterBuilder("%s.rules.moved_to_default".formatted(METRIC_NAMESPACE))
                .setDescription("Number of rules moved to Default Project (all original projects deleted)")
                .build();
        this.rulesSkipped = meter
                .counterBuilder("%s.project_associations.skipped".formatted(METRIC_NAMESPACE))
                .setDescription("Number of project associations skipped during migration, tagged by reason")
                .build();
    }

    public void runMigrationCycle() {
        var excludedWorkspaceIds = buildExcludedWorkspaceIds();

        log.info("Starting automation rule project migration cycle, workspacesPerRun='{}', "
                + "maxRulesPerCycle='{}', envExcludedWorkspaces='{}'",
                config.workspacesPerRun(), config.maxRulesPerCycle(),
                migrationConfig.getExcludedWorkspaceIds().size());

        var eligibleWorkspaces = findEligibleWorkspaces(excludedWorkspaceIds);

        cycleEligibleWorkspaces.record(eligibleWorkspaces.size());

        if (CollectionUtils.isEmpty(eligibleWorkspaces)) {
            log.info("No workspaces with multi-project automation rules found, consider disabling the job");
            return;
        }

        log.info("Found workspaces with multi-project automation rules, count='{}'", eligibleWorkspaces.size());

        int totalSplit = 0;
        int totalNewRules = 0;

        for (var workspace : eligibleWorkspaces) {
            var startMillis = System.currentTimeMillis();
            try {
                var result = migrateWorkspace(workspace.workspaceId(), workspace.multiProjectRuleCount());
                totalSplit += result.splitCount();
                totalNewRules += result.newRuleCount();
                recordWorkspaceDuration(RESULT_MIGRATED, startMillis);
            } catch (Exception e) {
                log.error("Workspace automation rule migration failed, will retry next cycle, workspaceId='{}'",
                        workspace.workspaceId(), e);
                recordWorkspaceDuration(RESULT_ERROR, startMillis);
            }
        }

        log.info("Automation rule project migration cycle completed, workspacesProcessed='{}', "
                + "totalRulesSplit='{}', totalNewRules='{}'",
                eligibleWorkspaces.size(), totalSplit, totalNewRules);
    }

    private Set<String> buildExcludedWorkspaceIds() {
        var envExcluded = migrationConfig.getExcludedWorkspaceIds();
        cycleEnvExcludedWorkspaces.set(envExcluded.size());
        return Set.copyOf(envExcluded);
    }

    private List<AutomationRuleMigrationDAO.EligibleWorkspace> findEligibleWorkspaces(
            Set<String> excludedWorkspaceIds) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findEligibleWorkspaces(excludedWorkspaceIds, DemoData.AUTOMATION_RULES,
                                config.workspacesPerRun()));
    }

    private WorkspaceMigrationResult migrateWorkspace(String workspaceId, long multiProjectRuleCount) {
        log.info("Starting workspace automation rule migration, workspaceId='{}', multiProjectRuleCount='{}'",
                workspaceId, multiProjectRuleCount);

        var rulesToProcess = findMultiProjectRuleIds(workspaceId);

        if (CollectionUtils.isEmpty(rulesToProcess)) {
            log.info("No multi-project automation rules to migrate, workspaceId='{}'", workspaceId);
            return WorkspaceMigrationResult.EMPTY;
        }

        log.info("Found multi-project rules to process, workspaceId='{}', count='{}'",
                workspaceId, rulesToProcess.size());

        int splitCount = 0;
        int newRuleCount = 0;
        int movedToDefaultCount = 0;

        for (var ruleId : rulesToProcess) {
            var result = splitRule(workspaceId, ruleId);
            switch (result.outcome()) {
                case SPLIT, PARTIAL_DELETED -> {
                    splitCount++;
                    newRuleCount += result.newRulesCreated();
                }
                case MOVED_TO_DEFAULT -> movedToDefaultCount++;
                case NO_OP -> log.debug("Rule already single-project (race), ruleId='{}', workspaceId='{}'",
                        ruleId, workspaceId);
            }
        }

        rulesSplit.add(splitCount);
        newRulesCreated.add(newRuleCount);

        evaluatorService.evictCache(workspaceId);

        log.info(
                "Workspace automation rule migration completed, workspaceId='{}', rulesSplit='{}', newRules='{}', movedToDefault='{}'",
                workspaceId, splitCount, newRuleCount, movedToDefaultCount);
        return WorkspaceMigrationResult.builder()
                .splitCount(splitCount)
                .newRuleCount(newRuleCount)
                .movedToDefaultCount(movedToDefaultCount)
                .build();
    }

    private List<UUID> findMultiProjectRuleIds(String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findMultiProjectRuleIds(workspaceId, DemoData.AUTOMATION_RULES,
                                config.maxRulesPerCycle()));
    }

    private SplitResult splitRule(String workspaceId, UUID ruleId) {
        var junctionProjectIds = findJunctionProjectIds(ruleId, workspaceId);

        if (junctionProjectIds.size() <= 1) {
            return SplitResult.builder().outcome(SplitOutcome.NO_OP).build();
        }

        var existingProjects = projectService.findByIds(workspaceId, junctionProjectIds);
        var validProjectIds = existingProjects.stream()
                .map(Project::id)
                .collect(Collectors.toUnmodifiableSet());

        var deletedCount = junctionProjectIds.size() - validProjectIds.size();

        if (validProjectIds.isEmpty()) {
            return moveRuleToDefaultProject(workspaceId, ruleId, deletedCount);
        }

        if (deletedCount > 0) {
            log.info("Rule has partially deleted projects, ruleId='{}', workspaceId='{}', valid='{}', deleted='{}'",
                    ruleId, workspaceId, validProjectIds.size(), deletedCount);
            rulesSkipped.add(deletedCount, SKIP_REASON_PARTIAL_DELETED);
        }

        var sortedProjectIds = validProjectIds.stream().sorted().toList();

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var migrationDao = handle.attach(AutomationRuleMigrationDAO.class);
            var ruleDao = handle.attach(AutomationRuleDAO.class);

            migrationDao.deleteJunctionByRuleId(ruleId, workspaceId);

            var firstProjectId = sortedProjectIds.getFirst();
            migrationDao.insertJunction(ruleId, firstProjectId, workspaceId);
            ruleDao.clearLegacyProjectId(ruleId, workspaceId);

            int newRules = 0;
            for (int i = 1; i < sortedProjectIds.size(); i++) {
                var newRuleId = idGenerator.generateId();
                var projectId = sortedProjectIds.get(i);

                migrationDao.copyBaseRule(newRuleId, ruleId, workspaceId);
                migrationDao.copyEvaluator(newRuleId, ruleId);
                migrationDao.insertJunction(newRuleId, projectId, workspaceId);
                ruleDao.clearLegacyProjectId(newRuleId, workspaceId);
                newRules++;
            }

            log.info("Split rule, ruleId='{}', workspaceId='{}', keptProject='{}', newRules='{}'",
                    ruleId, workspaceId, firstProjectId, newRules);

            return SplitResult.builder()
                    .outcome(deletedCount > 0 ? SplitOutcome.PARTIAL_DELETED : SplitOutcome.SPLIT)
                    .newRulesCreated(newRules)
                    .build();
        });
    }

    private SplitResult moveRuleToDefaultProject(String workspaceId, UUID ruleId, int deletedCount) {
        var defaultProject = projectService.getOrCreate(workspaceId, ProjectService.DEFAULT_PROJECT, SYSTEM_USER);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var migrationDao = handle.attach(AutomationRuleMigrationDAO.class);
            var ruleDao = handle.attach(AutomationRuleDAO.class);

            migrationDao.deleteJunctionByRuleId(ruleId, workspaceId);
            migrationDao.insertJunction(ruleId, defaultProject.id(), workspaceId);
            migrationDao.disableRule(ruleId, workspaceId);
            ruleDao.clearLegacyProjectId(ruleId, workspaceId);

            return null;
        });

        rulesMovedToDefault.add(1, MOVED_TO_DEFAULT_ATTR);
        log.info("All projects deleted for rule, moved to Default Project (disabled), "
                + "ruleId='{}', workspaceId='{}', deletedCount='{}', defaultProjectId='{}'",
                ruleId, workspaceId, deletedCount, defaultProject.id());

        return SplitResult.builder().outcome(SplitOutcome.MOVED_TO_DEFAULT).build();
    }

    private Set<UUID> findJunctionProjectIds(UUID ruleId, String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleProjectsDAO.class)
                        .findProjectIdsByRuleId(ruleId, workspaceId));
    }

    private void recordWorkspaceDuration(Attributes resultAttributes, long startMillis) {
        workspaceDuration.record(System.currentTimeMillis() - startMillis, resultAttributes);
    }

    private enum SplitOutcome {
        SPLIT,
        PARTIAL_DELETED,
        MOVED_TO_DEFAULT,
        NO_OP
    }

    @Builder
    private record SplitResult(SplitOutcome outcome, int newRulesCreated) {
    }

    @Builder
    private record WorkspaceMigrationResult(int splitCount, int newRuleCount, int movedToDefaultCount) {
        static final WorkspaceMigrationResult EMPTY = WorkspaceMigrationResult.builder().build();
    }
}
