package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AutomationRuleProjectMigrationJobTest {

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
                                new CustomConfig("cacheManager.enabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(clientSupport, baseUrl);
    }

    @Test
    void scheduledJobSplitsMultiProjectRule() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId1 = createProject(apiKey, workspaceName);
        var projectId2 = createProject(apiKey, workspaceName);

        createMultiProjectRule(apiKey, workspaceName, Set.of(projectId1, projectId2));

        Awaitility.await().atMost(31, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var rules1 = evaluatorResourceClient.findEvaluatorPage(
                            projectId1, null, null, null, 1, 100, workspaceName, apiKey);
                    var rules2 = evaluatorResourceClient.findEvaluatorPage(
                            projectId2, null, null, null, 1, 100, workspaceName, apiKey);
                    assertThat(rules1.content()).hasSize(1);
                    assertThat(rules1.content().get(0).getProjects()).hasSize(1);
                    assertThat(rules2.content()).hasSize(1);
                    assertThat(rules2.content().get(0).getProjects()).hasSize(1);
                });
    }

    private UUID createProject(String apiKey, String workspaceName) {
        return projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder()
                        .name(randomName("project"))
                        .build(),
                apiKey, workspaceName);
    }

    private UUID createMultiProjectRule(String apiKey, String workspaceName, Set<UUID> projectIds) {
        var evaluator = AutomationRuleEvaluatorLlmAsJudge.builder()
                .projectIds(projectIds)
                .name(randomName("rule"))
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

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(16));
    }
}
