package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.AutomationRuleEvaluatorUserDefinedMetricPython;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

//TODO: Remove this test class after finishing the implementation of the OnlineScoringEventListener class
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AutomationRuleEvaluatorServiceImplTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.randomAlphanumeric(20);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.randomAlphanumeric(20);
    public static final String[] IGNORED_FIELDS = {
            "createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy", "projectId"};

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    private final WireMockUtils.WireMockRuntime WIRE_MOCK;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER).join();

        WIRE_MOCK = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .runtimeInfo(WIRE_MOCK.runtimeInfo())
                        .customConfigs(
                                List.of(
                                        new TestDropwizardAppExtensionUtils.CustomConfig("cacheManager.enabled",
                                                "true"),
                                        new TestDropwizardAppExtensionUtils.CustomConfig("cacheManager.defaultDuration",
                                                "PT1S")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(WIRE_MOCK.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        var baseURI = "http://localhost:%d".formatted(client.getPort());
        this.evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
    }

    Stream<Class<? extends AutomationRuleEvaluator<?>>> getEvaluatorClass() {
        return Stream.of(
                AutomationRuleEvaluatorLlmAsJudge.class,
                AutomationRuleEvaluatorUserDefinedMetricPython.class);
    }

    @ParameterizedTest
    @MethodSource("getEvaluatorClass")
    void findAll__whenCacheIsEnabled__shouldCacheReturnedValue(
            Class<? extends AutomationRuleEvaluator<?>> evaluatorClass,
            AutomationRuleEvaluatorService service,
            TransactionTemplate transactionTemplate) {

        var projectName = "project-" + RandomStringUtils.randomAlphanumeric(20);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var evaluator = createEvaluator(evaluatorClass, projectId, projectName);

        var judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        // Going around the service to delete the evaluator
        deleteEvaluator(transactionTemplate, evaluator);

        judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());
    }

    private void deleteEvaluator(TransactionTemplate transactionTemplate, AutomationRuleEvaluator<?> evaluator) {
        transactionTemplate.inTransaction(TransactionTemplateAsync.WRITE, handle -> {
            handle.createUpdate("DELETE FROM automation_rule_evaluators WHERE id = :id")
                    .bind("id", evaluator.getId())
                    .execute();

            handle.createUpdate("DELETE FROM automation_rules WHERE id = :id")
                    .bind("id", evaluator.getId())
                    .execute();
            return null;
        });
    }

    private AutomationRuleEvaluator<?> createEvaluator(
            Class<? extends AutomationRuleEvaluator<?>> evaluatorClass, UUID projectId, String projectName) {
        return Optional.of(
                factory.manufacturePojo(evaluatorClass).toBuilder()
                        .projectId(projectId)
                        .build())
                .map(e -> e.toBuilder()
                        .id(evaluatorResourceClient.createEvaluator(e, WORKSPACE_NAME, API_KEY))
                        .projectName(projectName)
                        .build())
                .orElseThrow();
    }

    private void assertEvaluator(AutomationRuleEvaluator<?> expected, AutomationRuleEvaluator<?> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    private void assertEvaluator(Collection<AutomationRuleEvaluator<?>> expected,
            Collection<AutomationRuleEvaluator<Object>> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("getEvaluatorClass")
    void findAll__whenANewEvaluatorIsCreated__shouldInvalidateCache(
            Class<? extends AutomationRuleEvaluator<?>> evaluatorClass,
            AutomationRuleEvaluatorService service) {

        var projectName = "project-" + RandomStringUtils.randomAlphanumeric(20);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var evaluator = createEvaluator(evaluatorClass, projectId, projectName);

        var judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        var evaluator2 = createEvaluator(evaluatorClass, projectId, projectName);

        judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(2);
        assertEvaluator(Set.of(evaluator, evaluator2), judges);
    }

    Stream<Arguments> findAll__whenANewEvaluatorIsUpdated__shouldInvalidateCache() {
        return Stream.of(
                arguments(
                        AutomationRuleEvaluatorLlmAsJudge.class,
                        AutomationRuleEvaluatorUpdateLlmAsJudge.class),
                arguments(
                        AutomationRuleEvaluatorUserDefinedMetricPython.class,
                        AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class));
    }

    @ParameterizedTest
    @MethodSource
    void findAll__whenANewEvaluatorIsUpdated__shouldInvalidateCache(
            Class<? extends AutomationRuleEvaluator<?>> evaluatorClass,
            Class<? extends AutomationRuleEvaluatorUpdate<?>> evaluatorUpdateClass,
            AutomationRuleEvaluatorService service) {

        var projectName = "project-" + RandomStringUtils.randomAlphanumeric(20);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var evaluator = createEvaluator(evaluatorClass, projectId, projectName);

        var judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        var evaluatorUpdate = factory.manufacturePojo(evaluatorUpdateClass);
        service.update(evaluator.getId(), projectId, WORKSPACE_ID, USER, evaluatorUpdate);

        judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertThat(judges.getFirst().getName()).isEqualTo(evaluatorUpdate.getName());
    }

    @ParameterizedTest
    @MethodSource("getEvaluatorClass")
    void findAll__whenANewEvaluatorIsDeleted__shouldInvalidateCache(
            Class<? extends AutomationRuleEvaluator<?>> evaluatorClass,
            AutomationRuleEvaluatorService service) {

        var projectName = "project-" + RandomStringUtils.randomAlphanumeric(20);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var evaluator = createEvaluator(evaluatorClass, projectId, projectName);

        var judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).hasSize(1);
        assertEvaluator(evaluator, judges.getFirst());

        service.delete(Set.of(evaluator.getId()), projectId, WORKSPACE_ID);

        judges = service.findAll(projectId, WORKSPACE_ID);

        assertThat(judges).isEmpty();
    }
}