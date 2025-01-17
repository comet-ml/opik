package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

//TODO: Remove this test class after finsihing the implementation of the OnlineScoringEventListener class
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationRuleEvaluatorServiceImplTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + UUID.randomUUID();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension APP;

    private static final WireMockUtils.WireMockRuntime wireMock;
    public static final String[] IGNORED_FIELDS = {"createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy",
            "projectId"};

    static {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(
                                List.of(
                                        new TestDropwizardAppExtensionUtils.CustomConfig("cacheManager.enabled",
                                                "true"),
                                        new TestDropwizardAppExtensionUtils.CustomConfig("cacheManager.defaultDuration",
                                                "PT1S")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, WORKSPACE_NAME, WORKSPACE_ID);

        this.evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(this.client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Test
    void findAll__whenCacheIsEnabled__shouldCacheReturnedValue(AutomationRuleEvaluatorService service,
            TransactionTemplate transactionTemplate) {

        var projectName = factory.manufacturePojo(String.class);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);

        List<AutomationRuleEvaluatorLlmAsJudge> judges = service.findAll(projectId, WORKSPACE_ID,
                AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        // Going around the service to delete the evaluator
        deleteEvaluator(transactionTemplate, evaluator);

        judges = service.findAll(projectId, WORKSPACE_ID, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());
    }

    private void deleteEvaluator(TransactionTemplate transactionTemplate, AutomationRuleEvaluatorLlmAsJudge evaluator) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.createUpdate("DELETE FROM automation_rule_evaluators WHERE id = :id")
                    .bind("id", evaluator.getId())
                    .execute();

            handle.createUpdate("DELETE FROM automation_rules WHERE id = :id")
                    .bind("id", evaluator.getId())
                    .execute();
            return null;
        });
    }

    private AutomationRuleEvaluatorLlmAsJudge createEvaluator(UUID projectId) {
        return Optional.of(
                factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectId(projectId)
                        .build())
                .map(e -> e.toBuilder()
                        .id(evaluatorResourceClient.createEvaluator(e, projectId, WORKSPACE_NAME, API_KEY))
                        .build())
                .orElseThrow();
    }

    private void assertEvaluator(AutomationRuleEvaluatorLlmAsJudge expected, AutomationRuleEvaluatorLlmAsJudge actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields("createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy", "projectId")
                .isEqualTo(expected);
    }

    private void assertEvaluator(Collection<AutomationRuleEvaluatorLlmAsJudge> expected,
            Collection<AutomationRuleEvaluatorLlmAsJudge> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields("createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy", "projectId")
                .isEqualTo(expected);
    }

    @Test
    void findAll__whenANewEvaluatorIsCreated__shouldInvalidateCache(AutomationRuleEvaluatorService service,
            TransactionTemplate transactionTemplate) {

        var projectName = factory.manufacturePojo(String.class);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);

        List<AutomationRuleEvaluatorLlmAsJudge> judges = service.findAll(projectId, WORKSPACE_ID,
                AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        var evaluator2 = createEvaluator(projectId);

        judges = service.findAll(projectId, WORKSPACE_ID, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(2);
        assertEvaluator(Set.of(evaluator, evaluator2), judges);
    }

    @Test
    void findAll__whenANewEvaluatorIsUpdated__shouldInvalidateCache(AutomationRuleEvaluatorService service) {

        var projectName = factory.manufacturePojo(String.class);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);

        List<AutomationRuleEvaluatorLlmAsJudge> judges = service.findAll(projectId, WORKSPACE_ID,
                AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        var evaluatorUpdate = factory.manufacturePojo(AutomationRuleEvaluatorUpdate.class);

        service.update(evaluator.getId(), projectId, WORKSPACE_ID, USER, evaluatorUpdate);

        judges = service.findAll(projectId, WORKSPACE_ID, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertThat(judges.getFirst().getName()).isEqualTo(evaluatorUpdate.name());
    }

    @Test
    void findAll__whenANewEvaluatorIsDeleted__shouldInvalidateCache(AutomationRuleEvaluatorService service) {

        var projectName = factory.manufacturePojo(String.class);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);

        List<AutomationRuleEvaluatorLlmAsJudge> judges = service.findAll(projectId, WORKSPACE_ID,
                AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        service.delete(Set.of(evaluator.getId()), projectId, WORKSPACE_ID);

        judges = service.findAll(projectId, WORKSPACE_ID, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(judges).isEmpty();
    }
}