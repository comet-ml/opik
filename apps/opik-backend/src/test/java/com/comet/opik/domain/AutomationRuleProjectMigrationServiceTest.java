package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.evaluators.ProjectReference;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.domain.evaluators.AutomationRuleMigrationDAO;
import com.comet.opik.domain.evaluators.AutomationRuleProjectMigrationService;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AutomationRuleProjectMigrationServiceTest {

    private static final String EXCLUDED_WORKSPACE_ID = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("automationRuleProjectMigration.enabled", "true"),
                                new CustomConfig("migration.excludedWorkspaceIds", EXCLUDED_WORKSPACE_ID),
                                new CustomConfig("cacheManager.enabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(clientSupport, baseUrl);
        this.transactionTemplate = transactionTemplate;
    }

    @Test
    void fiveProjectRuleSplitsWithIdPreservation(AutomationRuleProjectMigrationService migrationService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectIds = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            projectIds.add(createProject(apiKey, workspaceName));
        }

        var ruleId = createMultiProjectRule(apiKey, workspaceName, Set.copyOf(projectIds));

        assertThat(hasVersion1AutomationRules(workspaceId)).isTrue();

        migrationService.runMigrationCycle();

        assertThat(hasVersion1AutomationRules(workspaceId)).isFalse();

        var sortedProjectIds = projectIds.stream().sorted().toList();

        for (var projectId : sortedProjectIds) {
            var page = evaluatorResourceClient.findEvaluatorPage(
                    projectId, null, null, null, 1, 100, workspaceName, apiKey);
            assertThat(page.content()).hasSize(1);
        }

        var firstProjectRule = evaluatorResourceClient.findEvaluatorPage(
                sortedProjectIds.get(0), null, null, null, 1, 100, workspaceName, apiKey)
                .content().get(0);
        assertThat(firstProjectRule.getId()).isEqualTo(ruleId);

        for (int i = 1; i < sortedProjectIds.size(); i++) {
            var rule = evaluatorResourceClient.findEvaluatorPage(
                    sortedProjectIds.get(i), null, null, null, 1, 100, workspaceName, apiKey)
                    .content().get(0);
            assertThat(rule.getId()).isNotEqualTo(ruleId);
        }
    }

    @Test
    void partialDeletedProjectSplitsValidOnly(AutomationRuleProjectMigrationService migrationService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        var sortedProjectIds = List.of(projectId1, projectId2).stream().sorted().toList();
        var firstProjectId = sortedProjectIds.get(0);
        var secondProjectId = sortedProjectIds.get(1);

        var ruleId = createMultiProjectRule(apiKey, workspaceName, Set.of(firstProjectId, secondProjectId));

        projectResourceClient.deleteProject(firstProjectId, apiKey, workspaceName);

        migrationService.runMigrationCycle();

        var rulesForSurvivor = evaluatorResourceClient.findEvaluatorPage(
                secondProjectId, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(rulesForSurvivor.content()).hasSize(1);
        assertThat(rulesForSurvivor.content().get(0).getId()).isEqualTo(ruleId);
    }

    @Test
    void allDeletedProjectTrapsWorkspace(
            AutomationRuleProjectMigrationService migrationService,
            WorkspacesService workspacesService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        createMultiProjectRule(apiKey, workspaceName, Set.of(projectId1, projectId2));

        projectResourceClient.deleteProject(projectId1, apiKey, workspaceName);
        projectResourceClient.deleteProject(projectId2, apiKey, workspaceName);

        migrationService.runMigrationCycle();

        assertThat(workspacesService.findAutomationRuleMigrationSkippedWorkspaceIds())
                .contains(workspaceId);
    }

    @Test
    void idempotentRerunIsNoOp(AutomationRuleProjectMigrationService migrationService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        createMultiProjectRule(apiKey, workspaceName, Set.of(projectId1, projectId2));

        migrationService.runMigrationCycle();

        var rulesAfterFirst = evaluatorResourceClient.findEvaluatorPage(
                projectId1, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(rulesAfterFirst.content()).hasSize(1);
        var ruleIdAfterFirst = rulesAfterFirst.content().get(0).getId();

        migrationService.runMigrationCycle();

        var rulesAfterSecond = evaluatorResourceClient.findEvaluatorPage(
                projectId1, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(rulesAfterSecond.content()).hasSize(1);
        assertThat(rulesAfterSecond.content().get(0).getId()).isEqualTo(ruleIdAfterFirst);
    }

    @Test
    void excludedWorkspaceNeverTouched(AutomationRuleProjectMigrationService migrationService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, EXCLUDED_WORKSPACE_ID, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        createMultiProjectRule(apiKey, workspaceName, Set.of(projectId1, projectId2));

        assertThat(hasVersion1AutomationRules(EXCLUDED_WORKSPACE_ID)).isTrue();

        migrationService.runMigrationCycle();

        assertThat(hasVersion1AutomationRules(EXCLUDED_WORKSPACE_ID)).isTrue();

        var rules = evaluatorResourceClient.findEvaluatorPage(
                projectId1, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(rules.content()).hasSize(1);
        var returnedProjectIds = rules.content().get(0).getProjects().stream()
                .map(ProjectReference::projectId)
                .collect(Collectors.toSet());
        assertThat(returnedProjectIds).containsExactlyInAnyOrder(projectId1, projectId2);
    }

    @Test
    void cacheEvictedAfterMigration(AutomationRuleProjectMigrationService migrationService) {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        createMultiProjectRule(apiKey, workspaceName, Set.of(projectId1, projectId2));

        var beforeMigration = evaluatorResourceClient.findEvaluatorPage(
                projectId1, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(beforeMigration.content()).hasSize(1);
        assertThat(beforeMigration.content().get(0).getProjects()).hasSize(2);

        migrationService.runMigrationCycle();

        var afterMigration = evaluatorResourceClient.findEvaluatorPage(
                projectId1, null, null, null, 1, 100, workspaceName, apiKey);
        assertThat(afterMigration.content()).hasSize(1);
        assertThat(afterMigration.content().get(0).getProjects()).hasSize(1);
    }

    @Test
    void demoNamedRulesExcludedFromEligibility() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        var demoRuleName = randomName("demo-rule");
        createMultiProjectRuleWithName(apiKey, workspaceName, Set.of(projectId1, projectId2), demoRuleName);

        var withoutExclusion = transactionTemplate.inTransaction(
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findEligibleWorkspaces(Set.of("__placeholder__"), List.of(), 100));
        assertThat(withoutExclusion).anyMatch(w -> w.workspaceId().equals(workspaceId));

        var withExclusion = transactionTemplate.inTransaction(
                handle -> handle.attach(AutomationRuleMigrationDAO.class)
                        .findEligibleWorkspaces(Set.of("__placeholder__"), List.of(demoRuleName), 100));
        assertThat(withExclusion).noneMatch(w -> w.workspaceId().equals(workspaceId));
    }

    private UUID createProject(String apiKey, String workspaceName) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder()
                        .name(randomName("project"))
                        .build(),
                apiKey, workspaceName);
    }

    private UUID createMultiProjectRule(String apiKey, String workspaceName, Set<UUID> projectIds) {
        return createMultiProjectRuleWithName(apiKey, workspaceName, projectIds, randomName("rule"));
    }

    private UUID createMultiProjectRuleWithName(String apiKey, String workspaceName, Set<UUID> projectIds,
            String name) {
        var evaluator = AutomationRuleEvaluatorLlmAsJudge.builder()
                .projectIds(projectIds)
                .name(name)
                .samplingRate(1.0f)
                .enabled(true)
                .code(new LlmAsJudgeCode(
                        new LlmAsJudgeModelParameters("gpt-4", 0.7, null, null),
                        List.of(new LlmAsJudgeMessage(ChatMessageType.SYSTEM,
                                "You are a helpful assistant.", null)),
                        Map.of("input", "{{input}}"),
                        List.of(new LlmAsJudgeOutputSchema("score",
                                LlmAsJudgeOutputSchemaType.INTEGER, "A score from 1 to 10"))))
                .build();
        return evaluatorResourceClient.createEvaluator(evaluator, workspaceName, apiKey);
    }

    private boolean hasVersion1AutomationRules(String workspaceId) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(AutomationRuleDAO.class).hasVersion1AutomationRules(workspaceId));
    }

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(16));
    }
}
